package org.embulk.input.marketo;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigException;
import org.embulk.spi.DataException;
import org.embulk.spi.Exec;
import org.embulk.spi.util.LineDecoder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Created by tai.khuu on 9/15/17.
 */
public class CsvTokenizer
{
    static enum RecordState
    {
        NOT_END, END,
    }

    static enum ColumnState
    {
        BEGIN, VALUE, QUOTED_VALUE, AFTER_QUOTED_VALUE, FIRST_TRIM, LAST_TRIM_OR_VALUE,
    }

    private static final char END_OF_LINE = '\0';
    static final char NO_QUOTE = '\0';
    static final char NO_ESCAPE = '\0';

    public interface PluginTask extends LineDecoder.DecoderTask
    {
        @Config("delimiter")
        @ConfigDefault("\",\"")
        String getDelimiter();

        @Config("quote")
        @ConfigDefault("\"\\\"\"")
        Optional<QuoteCharacter> getQuoteChar();

        @Config("escape")
        @ConfigDefault("\"\\\\\"")
        Optional<EscapeCharacter> getEscapeChar();

        // Null value handling: if the CsvParser found 'non-quoted empty string's,
        // it replaces them to string that users specified like "\N", "NULL".
        @Config("null_string")
        @ConfigDefault("\"null\"")
        Optional<String> getNullString();

        @Config("trim_if_not_quoted")
        @ConfigDefault("false")
        boolean getTrimIfNotQuoted();

        @Config("max_quoted_size_limit")
        @ConfigDefault("131072") //128kB
        long getMaxQuotedSizeLimit();

        @Config("comment_line_marker")
        @ConfigDefault("null")
        Optional<String> getCommentLineMarker();
    }

    private final char delimiterChar;
    private final String delimiterFollowingString;
    private final char quote;
    private final char escape;
    private final String newline;
    private final boolean trimIfNotQuoted;
    private final long maxQuotedSizeLimit;
    private final String commentLineMarker;
    private final LineDecoder input;
    private final String nullStringOrNull;

    private RecordState recordState = RecordState.END;  // initial state is end of a record. nextRecord() must be called first
    private long lineNumber = 0;

    private String line = null;
    private int linePos = 0;
    private boolean wasQuotedColumn = false;
    private List<String> quotedValueLines = new ArrayList<>();
    private Deque<String> unreadLines = new ArrayDeque<>();

    public CsvTokenizer(LineDecoder input, PluginTask task)
    {
        this(task.getDelimiter(), task.getQuoteChar().or(QuoteCharacter.noQuote()).getCharacter(),
                task.getEscapeChar().or(EscapeCharacter.noEscape()).getCharacter(), task.getNewline().getString(),
                task.getTrimIfNotQuoted(), task.getMaxQuotedSizeLimit(), task.getCommentLineMarker().orNull(), input, task.getNullString().orNull());
    }

    public CsvTokenizer(String delimiter, char quote, char escape, String newline, boolean trimIfNotQuoted, long maxQuotedSizeLimit, String commentLineMarker, LineDecoder input, String nullStringOrNull)
    {
        if (delimiter.length() == 0) {
            throw new ConfigException("Empty delimiter is not allowed");
        }
        else {
            this.delimiterChar = delimiter.charAt(0);
            if (delimiter.length() > 1) {
                delimiterFollowingString = delimiter.substring(1);
            }
            else {
                delimiterFollowingString = null;
            }
        }
        this.quote = quote;
        this.escape = escape;
        this.newline = newline;
        this.trimIfNotQuoted = trimIfNotQuoted;
        this.maxQuotedSizeLimit = maxQuotedSizeLimit;
        this.commentLineMarker = commentLineMarker;
        this.input = input;
        this.nullStringOrNull = nullStringOrNull;
    }

    public long getCurrentLineNumber()
    {
        return lineNumber;
    }

    public boolean skipHeaderLine()
    {
        boolean skipped = input.poll() != null;
        if (skipped) {
            lineNumber++;
        }
        return skipped;
    }

    // returns skipped line
    public String skipCurrentLine()
    {
        String skippedLine;
        if (quotedValueLines.isEmpty()) {
            skippedLine = line;
        }
        else {
            // recover lines of quoted value
            skippedLine = quotedValueLines.remove(0);  // TODO optimize performance
            unreadLines.addAll(quotedValueLines);
            lineNumber -= quotedValueLines.size();
            if (line != null) {
                unreadLines.add(line);
                lineNumber -= 1;
            }
            quotedValueLines.clear();
        }
        recordState = RecordState.END;
        return skippedLine;
    }

    public boolean nextFile()
    {
        boolean next = input.nextFile();
        if (next) {
            lineNumber = 0;
        }
        return next;
    }

    // used by guess-csv
    public boolean nextRecord()
    {
        return nextRecord(true);
    }

    public boolean nextRecord(boolean skipEmptyLine)
    {
        // If at the end of record, read the next line and initialize the state
        if (recordState != RecordState.END) {
            throw new TooManyColumnsException("Too many columns");
        }

        boolean hasNext = nextLine(skipEmptyLine);
        if (hasNext) {
            recordState = RecordState.NOT_END;
            return true;
        }
        else {
            return false;
        }
    }

    private boolean nextLine(boolean skipEmptyLine)
    {
        while (true) {
            if (!unreadLines.isEmpty()) {
                line = unreadLines.removeFirst();
            }
            else {
                line = input.poll();
                if (line == null) {
                    return false;
                }
            }
            linePos = 0;
            lineNumber++;

            boolean skip = skipEmptyLine && (
                    line.isEmpty() ||
                            (commentLineMarker != null && line.startsWith(commentLineMarker)));
            if (!skip) {
                return true;
            }
        }
    }
    private char peekNextNextChar() {
        Preconditions.checkState(line != null, "peekNextNextChar is called after end of file");

        if (linePos + 1 >= line.length()) {
            return END_OF_LINE;
        } else {
            return line.charAt(linePos + 1);
        }
    }
    public boolean hasNextColumn()
    {
        return recordState == RecordState.NOT_END;
    }

    public String nextColumn()
    {
        String quotesInQuotedFields = "ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS2";
        if (!hasNextColumn()) {
            throw new TooFewColumnsException("Too few columns");
        }

        // reset last state
        wasQuotedColumn = false;
        quotedValueLines.clear();

        // local state
        int valueStartPos = linePos;
        int valueEndPos = 0;  // initialized by VALUE state and used by LAST_TRIM_OR_VALUE and
        StringBuilder quotedValue = null;  // initial by VALUE or FIRST_TRIM state and used by QUOTED_VALUE state
        ColumnState columnState = ColumnState.BEGIN;

        while (true) {
            final char c = nextChar();

            switch (columnState) {
                case BEGIN:
                    // TODO optimization: state is BEGIN only at the first character of a column.
                    //      this block can be out of the looop.
                    if (isDelimiter(c)) {
                        // empty value
                        if (delimiterFollowingString == null) {
                            return "";
                        }
                        else if (isDelimiterFollowingFrom(linePos)) {
                            linePos += delimiterFollowingString.length();
                            return "";
                        }
                        // not a delimiter
                    }
                    if (isEndOfLine(c)) {
                        // empty value
                        recordState = RecordState.END;
                        return "";
                    }
                    else if (isSpace(c) && trimIfNotQuoted) {
                        columnState = ColumnState.FIRST_TRIM;
                    }
                    else if (isQuote(c)) {
                        valueStartPos = linePos;  // == 1
                        wasQuotedColumn = true;
                        quotedValue = new StringBuilder();
                        columnState = ColumnState.QUOTED_VALUE;
                    }
                    else {
                        columnState = ColumnState.VALUE;
                    }
                    break;

                case FIRST_TRIM:
                    if (isDelimiter(c)) {
                        // empty value
                        if (delimiterFollowingString == null) {
                            return "";
                        }
                        else if (isDelimiterFollowingFrom(linePos)) {
                            linePos += delimiterFollowingString.length();
                            return "";
                        }
                        // not a delimiter
                    }
                    if (isEndOfLine(c)) {
                        // empty value
                        recordState = RecordState.END;
                        return "";
                    }
                    else if (isQuote(c)) {
                        // column has heading spaces and quoted. TODO should this be rejected?
                        valueStartPos = linePos;
                        wasQuotedColumn = true;
                        quotedValue = new StringBuilder();
                        columnState = ColumnState.QUOTED_VALUE;
                    }
                    else if (isSpace(c)) {
                        // skip this character
                    } else {
                        valueStartPos = linePos - 1;
                        columnState = ColumnState.VALUE;
                    }
                    break;

                case VALUE:
                    if (isDelimiter(c)) {
                        if (delimiterFollowingString == null) {
                            return line.substring(valueStartPos, linePos - 1);
                        }
                        else if (isDelimiterFollowingFrom(linePos)) {
                            String value = line.substring(valueStartPos, linePos - 1);
                            linePos += delimiterFollowingString.length();
                            return value;
                        }
                        // not a delimiter
                    }
                    if (isEndOfLine(c)) {
                        recordState = RecordState.END;
                        return line.substring(valueStartPos, linePos);
                    }
                    else if (isSpace(c) && trimIfNotQuoted) {
                        valueEndPos = linePos - 1;  // this is possibly end of value
                        columnState = ColumnState.LAST_TRIM_OR_VALUE;

                        // TODO not implemented yet foo""bar""baz -> [foo, bar, baz].append
                        //} else if (isQuote(c)) {
                        //    // In RFC4180, If fields are not enclosed with double quotes, then
                        //    // double quotes may not appear inside the fields. But they are often
                        //    // included in the fields. We should care about them later.
                    }
                    else {
                        // keep VALUE state
                    }
                    break;

                case LAST_TRIM_OR_VALUE:
                    if (isDelimiter(c)) {
                        if (delimiterFollowingString == null) {
                            return line.substring(valueStartPos, valueEndPos);
                        }
                        else if (isDelimiterFollowingFrom(linePos)) {
                            linePos += delimiterFollowingString.length();
                            return line.substring(valueStartPos, valueEndPos);
                        }
                        else {
                            // not a delimiter
                        }
                    }
                    if (isEndOfLine(c)) {
                        recordState = RecordState.END;
                        return line.substring(valueStartPos, valueEndPos);
                    }
                    else if (isSpace(c)) {
                        // keep LAST_TRIM_OR_VALUE state
                    } else {
                        // this spaces are not trailing spaces. go back to VALUE state
                        columnState = ColumnState.VALUE;
                    }
                    break;

                case QUOTED_VALUE:
                    if (isEndOfLine(c)) {
                        // multi-line quoted value
                        quotedValue.append(line.substring(valueStartPos, linePos));
                        quotedValue.append(newline);
                        quotedValueLines.add(line);
                        if (!nextLine(false)) {
                            throw new InvalidValueException("Unexpected end of line during parsing a quoted value");
                        }
                        valueStartPos = 0;
                    }
                    else if (isQuote(c)) {
                        char next = peekNextChar();
                        final char nextNext = peekNextNextChar();
//                        Exec.getLogger(CsvTokenizer.class).info("Character is {}. Next: {}, NextNext:{}", c,next,nextNext);
                        if (isQuote(next)
                                && (quotesInQuotedFields != "ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS"
                                || (!isDelimiter(nextNext) && !isEndOfLine(nextNext)))) {
                            // Escaped by preceding it with another quote.
                            // A quote just before a delimiter or an end of line is recognized as a functional quote,
                            // not just as a non-escaped stray "quote character" included the field, even if
                            // ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS is specified.
                            quotedValue.append(line.substring(valueStartPos, linePos));
                            valueStartPos = ++linePos;
                        } else if (quotesInQuotedFields == "ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS"
                                && !(isDelimiter(next) || isEndOfLine(next))) {
                            // A non-escaped stray "quote character" in the field is processed as a regular character
                            // if ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS is specified,
                            if ((linePos - valueStartPos) + quotedValue.length() > maxQuotedSizeLimit) {
                                throw new QuotedSizeLimitExceededException("The size of the quoted value exceeds the limit size (" + maxQuotedSizeLimit + ")");
                            }
                        } else {
                            quotedValue.append(line.substring(valueStartPos, linePos - 1));
//                            Exec.getLogger(CsvTokenizer.class).info("Current column state is {}. Quoted Value : {}", columnState, quotedValue);
                            columnState = ColumnState.AFTER_QUOTED_VALUE;
                        }
                    }
                    else if (isEscape(c)) {  // isQuote must be checked first in case of quote == escape
                        // In RFC 4180, CSV's escape char is '\"'. But '\\' is often used.
                        char next = peekNextChar();
//                        Exec.getLogger(CsvTokenizer.class).info("Esc Character is {}. Next: {}, column state{}", c,next, columnState);
                        if (isEndOfLine(c)) {
                            // escape end of line. TODO assuming multi-line quoted value without newline?
                            quotedValue.append(line.substring(valueStartPos, linePos));
                            quotedValueLines.add(line);
                            if (!nextLine(false)) {
                                throw new InvalidValueException("Unexpected end of line during parsing a quoted value");
                            }
                            valueStartPos = 0;
                        }
                        else if ( isEscape(next)) { // escaped quote
                            quotedValue.append(line.substring(valueStartPos, linePos - 1));
                            quotedValue.append(next);
                            valueStartPos = ++linePos;
                        }
                    }
                    else {
                        if ((linePos - valueStartPos) + quotedValue.length() > maxQuotedSizeLimit) {
                            throw new QuotedSizeLimitExceededException("The size of the quoted value exceeds the limit size (" + maxQuotedSizeLimit + ")");
                        }
                        // keep QUOTED_VALUE state
                    }
                    break;

                case AFTER_QUOTED_VALUE:

                    if (isDelimiter(c)) {
                        if (delimiterFollowingString == null) {
                            return quotedValue.toString();
                        }
                        else if (isDelimiterFollowingFrom(linePos)) {
                            linePos += delimiterFollowingString.length();
                            return quotedValue.toString();
                        }
                        // not a delimiter
                    }
                    if (isEndOfLine(c)) {
                        recordState = RecordState.END;
                        return quotedValue.toString();
                    }
                    else if (isSpace(c)) {
                        // column has trailing spaces and quoted. TODO should this be rejected?
                    } else {
                        // I do not see a reason to reject record if stray quotes happen:
                        // ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS	Accept stray quotes as-is in the field. Instead, it behaves undefined if delimiters are in fields. "a"b" goes a"b. "a""b" goes a"b.
                        // https://www.embulk.org/docs/built-in.html#csv-parser-plugin
                        // throw new InvalidValueException(String.format("Unexpected extra character '%c' after a value quoted by '%c'", c, quote));
                        Exec.getLogger(CsvTokenizer.class).warn(String.format("Unexpected extra character '%c' after a value quoted by '%c', Record= %s", c, quote, line));

                    }
                    break;

                default:
                    assert false;
            }
        }
    }

    public String nextColumnOrNull()
    {
        String v = nextColumn();
        if (nullStringOrNull == null) {
            if (v.isEmpty()) {
                if (wasQuotedColumn) {
                    return "";
                }
                else {
                    return null;
                }
            }
            else {
                return v;
            }
        }
        else {
            if (v.equals(nullStringOrNull)) {
                return null;
            }
            else {
                return v;
            }
        }
    }

    public boolean wasQuotedColumn()
    {
        return wasQuotedColumn;
    }

    private char nextChar()
    {
        Preconditions.checkState(line != null, "nextColumn is called after end of file");

        if (linePos >= line.length()) {
            return END_OF_LINE;
        }
        else {
            return line.charAt(linePos++);
        }
    }

    private char peekNextChar()
    {
        Preconditions.checkState(line != null, "peekNextChar is called after end of file");

        if (linePos >= line.length()) {
            return END_OF_LINE;
        }
        else {
            return line.charAt(linePos);
        }
    }

    private boolean isSpace(char c)
    {
        return c == ' ';
    }

    private boolean isDelimiterFollowingFrom(int pos)
    {
        if (line.length() < pos + delimiterFollowingString.length()) {
            return false;
        }
        for (int i = 0; i < delimiterFollowingString.length(); i++) {
            if (delimiterFollowingString.charAt(i) != line.charAt(pos + i)) {
                return false;
            }
        }
        return true;
    }

    private boolean isDelimiter(char c)
    {
        return c == delimiterChar;
    }

    private boolean isEndOfLine(char c)
    {
        return c == END_OF_LINE;
    }

    private boolean isQuote(char c)
    {
        return quote != NO_QUOTE && c == quote;
    }

    private boolean isEscape(char c)
    {
        return escape != NO_ESCAPE && c == escape;
    }

    public static class InvalidFormatException
            extends DataException
    {
        public InvalidFormatException(String message)
        {
            super(message);
        }
    }

    public static class InvalidValueException
            extends DataException
    {
        public InvalidValueException(String message)
        {
            super(message);
        }
    }

    public static class QuotedSizeLimitExceededException
            extends InvalidValueException
    {
        public QuotedSizeLimitExceededException(String message)
        {
            super(message);
        }
    }

    public class TooManyColumnsException
            extends InvalidFormatException
    {
        public TooManyColumnsException(String message)
        {
            super(message);
        }
    }

    public class TooFewColumnsException
            extends InvalidFormatException
    {
        public TooFewColumnsException(String message)
        {
            super(message);
        }
    }

    public static class QuoteCharacter
    {
        private final char character;

        public QuoteCharacter(char character)
        {
            this.character = character;
        }

        public static QuoteCharacter noQuote()
        {
            return new QuoteCharacter(CsvTokenizer.NO_QUOTE);
        }

        @JsonCreator
        public static QuoteCharacter ofString(String str)
        {
            if (str.length() >= 2) {
                throw new ConfigException("\"quote\" option accepts only 1 character.");
            }
            else if (str.isEmpty()) {
                Exec.getLogger(CsvTokenizer.class).warn("Setting '' (empty string) to \"quote\" option is obsoleted. Currently it becomes '\"' automatically but this behavior will be removed. Please set '\"' explicitly.");
                return new QuoteCharacter('"');
            }
            else {
                return new QuoteCharacter(str.charAt(0));
            }
        }

        @JsonIgnore
        public char getCharacter()
        {
            return character;
        }

        @JsonValue
        public String getOptionalString()
        {
            return new String(new char[] { character });
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + character;
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (!(obj instanceof QuoteCharacter)) {
                return false;
            }
            QuoteCharacter o = (QuoteCharacter) obj;
            return character == o.character;
        }
    }

    public static class EscapeCharacter
    {
        private final char character;

        public EscapeCharacter(char character)
        {
            this.character = character;
        }

        public static EscapeCharacter noEscape()
        {
            return new EscapeCharacter(CsvTokenizer.NO_ESCAPE);
        }

        @JsonCreator
        public static EscapeCharacter ofString(String str)
        {
            if (str.length() >= 2) {
                throw new ConfigException("\"escape\" option accepts only 1 character.");
            }
            else if (str.isEmpty()) {
                Exec.getLogger(CsvTokenizer.class).warn("Setting '' (empty string) to \"escape\" option is obsoleted. Currently it becomes null automatically but this behavior will be removed. Please set \"escape: null\" explicitly.");
                return noEscape();
            }
            else {
                return new EscapeCharacter(str.charAt(0));
            }
        }

        @JsonIgnore
        public char getCharacter()
        {
            return character;
        }

        @JsonValue
        public String getOptionalString()
        {
            return new String(new char[] { character });
        }

        @Override
        public boolean equals(Object obj)
        {
            if (!(obj instanceof EscapeCharacter)) {
                return false;
            }
            EscapeCharacter o = (EscapeCharacter) obj;
            return character == o.character;
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + character;
            return result;
        }
    }
}
