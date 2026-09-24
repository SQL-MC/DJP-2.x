package semmiedev.disc_jockey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class Lyrics {
    public record Line(long timeMs, String text) {
    }

    
    private record RawLine(String content, String terminator) {
    }

    
    private record Source(String text, Charset charset, boolean byteOrderMark) {
    }

    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:([.:])(\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TIMESTAMP = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:([.:])(\\d{1,3}))?>");
    private static final Pattern METADATA = Pattern.compile("\\[(ti|ar|al|by|au|re|ve|length):[^]]*]", Pattern.CASE_INSENSITIVE);
    
    private static final Charset GB18030 = Charset.forName("GB18030");

    private final Path file;
    private final Charset charset;
    private final boolean byteOrderMark;
    
    private final List<RawLine> rawLines;
    
    private final long tagOffsetMillis;
    
    private long offsetMillis;

    private List<Line> lines;

    private Lyrics(Path file, Charset charset, boolean byteOrderMark, List<RawLine> rawLines, long tagOffsetMillis) {
        this.file = file;
        this.charset = charset;
        this.byteOrderMark = byteOrderMark;
        this.rawLines = rawLines;
        this.tagOffsetMillis = tagOffsetMillis;
        this.lines = List.of();
    }

    
    public static Lyrics parse(Path file) throws IOException {
        Source source = read(file);

        long tagOffset = 0;
        Matcher offsetMatcher = OFFSET.matcher(source.text());
        if (offsetMatcher.find()) {
            try {
                tagOffset = Long.parseLong(offsetMatcher.group(1));
            } catch (NumberFormatException ignored) {
                
            }
        }

        Lyrics lyrics = new Lyrics(file, source.charset(), source.byteOrderMark(), splitLines(source.text()), tagOffset);
        lyrics.rebuild();
        return lyrics.lines.isEmpty() ? null : lyrics;
    }

    
    private void rebuild() {
        List<Line> parsed = new ArrayList<>();
        for (RawLine rawLine : rawLines) {
            String line = METADATA.matcher(rawLine.content()).replaceAll("");
            Matcher matcher = TIMESTAMP.matcher(line);
            
            
            String text = WORD_TIMESTAMP.matcher(matcher.replaceAll("")).replaceAll("").trim();
            if (text.isEmpty()) continue;
            matcher.reset();
            while (matcher.find()) {
                parsed.add(new Line(Math.max(0, timestamp(matcher) + tagOffsetMillis + offsetMillis), text));
            }
        }
        parsed.sort(Comparator.comparingLong(Line::timeMs));
        this.lines = parsed;
    }

    
    public long shiftBy(long deltaMillis) throws IOException {
        long previous = offsetMillis;
        offsetMillis += deltaMillis;
        rebuild();
        try {
            write();
        } catch (IOException exception) {
            
            offsetMillis = previous;
            rebuild();
            throw exception;
        }
        return offsetMillis;
    }

    
    public long offsetMillis() {
        return offsetMillis;
    }
    
    

    public void setOffsetMillis(long value) throws IOException {
        long delta = value - this.offsetMillis;
        long previous = this.offsetMillis;
        this.offsetMillis += delta;
        try {
            write();
        } catch (IOException e) {
            this.offsetMillis = previous;
            throw e;
        }
    }

    public void save() throws IOException {
        write();
    }
    
    public Path file() {
        return file;
    }

    private static long timestamp(Matcher matcher) {
        long minutes = Long.parseLong(matcher.group(1));
        long seconds = Long.parseLong(matcher.group(2));
        String fraction = matcher.group(4);
        long millis = 0;
        if (fraction != null && !fraction.isEmpty()) {
            millis = switch (fraction.length()) {
                case 1 -> Long.parseLong(fraction) * 100;   
                case 2 -> Long.parseLong(fraction) * 10;    
                default -> Long.parseLong(fraction.substring(0, 3)); 
            };
        }
        return minutes * 60_000L + seconds * 1_000L + millis;
    }

    private static Source read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        boolean byteOrderMark = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
        int start = byteOrderMark ? 3 : 0;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start))
                    .toString();
            return new Source(text, StandardCharsets.UTF_8, byteOrderMark);
        } catch (CharacterCodingException exception) {
            
            
            return new Source(GB18030.decode(ByteBuffer.wrap(bytes)).toString(), GB18030, false);
        }
    }

    
    private static List<RawLine> splitLines(String content) {
        List<RawLine> result = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < content.length()) {
            char character = content.charAt(index);
            if (character == '\n') {
                result.add(new RawLine(content.substring(start, index), "\n"));
            } else if (character == '\r') {
                String terminator = index + 1 < content.length() && content.charAt(index + 1) == '\n' ? "\r\n" : "\r";
                result.add(new RawLine(content.substring(start, index), terminator));
                index += terminator.length();
                start = index;
                continue;
            } else {
                index++;
                continue;
            }
            index++;
            start = index;
        }
        if (start < content.length() || result.isEmpty()) result.add(new RawLine(content.substring(start), ""));
        return result;
    }

    
    public void write() throws IOException {
        byte[] bytes = encode(shiftedContent());
        Path target = file.toAbsolutePath();
        
        
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] encode(String content) {
        byte[] encoded = content.getBytes(charset);
        if (!byteOrderMark) return encoded;
        byte[] bytes = new byte[encoded.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(encoded, 0, bytes, 3, encoded.length);
        return bytes;
    }

    private String shiftedContent() {
        StringBuilder content = new StringBuilder();
        for (RawLine rawLine : rawLines) {
            String line = shiftedTimestamps(rawLine.content(), TIMESTAMP, '[', ']');
            
            
            
            content.append(shiftedTimestamps(line, WORD_TIMESTAMP, '<', '>')).append(rawLine.terminator());
        }
        return content.toString();
    }

    
    private String shiftedTimestamps(String line, Pattern pattern, char open, char close) {
        Matcher matcher = pattern.matcher(line);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String shifted = formatTimestamp(matcher, timestamp(matcher) + offsetMillis, open, close);
            matcher.appendReplacement(result, Matcher.quoteReplacement(shifted));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    
    private static String formatTimestamp(Matcher matcher, long shiftedMillis, char open, char close) {
        long millis = Math.max(0, shiftedMillis);
        String fraction = matcher.group(4);
        long step = switch (fraction == null ? 0 : fraction.length()) {
            case 0 -> 1000; 
            case 1 -> 100;
            case 2 -> 10;
            default -> 1;
        };
        millis = Math.round(millis / (double) step) * step;
        long totalSeconds = millis / 1000;
        StringBuilder result = new StringBuilder().append(open)
                .append(pad(totalSeconds / 60, matcher.group(1).length()))
                .append(':')
                .append(pad(totalSeconds % 60, matcher.group(2).length()));
        if (fraction != null) {
            // The separator is kept as it was, '.' in normal files and ':' in a few older ones.
            result.append(matcher.group(3)).append(pad(millis % 1000 / step, fraction.length()));
        }
        return result.append(close).toString();
    }

    private static String pad(long value, int digits) {
        
        return String.format(Locale.ROOT, "%0" + digits + "d", value);
    }

    public int size() {
        return lines.size();
    }

    public Line line(int index) {
        return lines.get(index);
    }

    
    public int indexAt(long songMs) {
        int low = 0;
        int high = lines.size() - 1;
        int result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).timeMs() <= songMs) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }
}
