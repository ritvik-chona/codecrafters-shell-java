import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Completer;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class Main {

    private static final Set<String> builtins = new HashSet<>(
            Set.of("echo", "exit", "type", "pwd", "cd", "complete"));

    private static String currentDir = System.getProperty("user.dir");

    // stores registrations from: complete -C <script> <command>
    private static final Map<String, String> completionSpecs = new HashMap<>();

    // shared reference so the completer can access the terminal
    private static Terminal terminal;

    private static class Redirection {
        String stdoutFile  = null;
        String stderrFile  = null;
        boolean appendStdout = false;
        boolean appendStderr = false;
    }

    // ── Tokenizer ────────────────────────────────────────────────────────────

    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;

        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
                if (i < line.length()) { current.append(line.charAt(i)); i++; }
            } else if (c == '\'') {
                i++;
                while (i < line.length() && line.charAt(i) != '\'') {
                    current.append(line.charAt(i)); i++;
                }
                i++;
            } else if (c == '"') {
                i++;
                while (i < line.length() && line.charAt(i) != '"') {
                    char d = line.charAt(i);
                    if (d == '\\' && i + 1 < line.length()) {
                        char next = line.charAt(i + 1);
                        if (next == '\\' || next == '"' || next == '$' || next == '`') {
                            current.append(next); i += 2;
                        } else { current.append(d); i++; }
                    } else { current.append(d); i++; }
                }
                i++;
            } else if (c == ' ' || c == '\t') {
                if (current.length() > 0) { tokens.add(current.toString()); current.setLength(0); }
                i++;
            } else { current.append(c); i++; }
        }

        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    // ── Redirection ──────────────────────────────────────────────────────────

    private static List<String> extractRedirections(List<String> tokens, Redirection redir) {
        List<String> clean = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if ((t.equals(">") || t.equals("1>")) && i + 1 < tokens.size()) {
                redir.stdoutFile = tokens.get(i + 1); redir.appendStdout = false; i += 2;
            } else if ((t.equals(">>") || t.equals("1>>")) && i + 1 < tokens.size()) {
                redir.stdoutFile = tokens.get(i + 1); redir.appendStdout = true; i += 2;
            } else if (t.equals("2>") && i + 1 < tokens.size()) {
                redir.stderrFile = tokens.get(i + 1); redir.appendStderr = false; i += 2;
            } else if (t.equals("2>>") && i + 1 < tokens.size()) {
                redir.stderrFile = tokens.get(i + 1); redir.appendStderr = true; i += 2;
            } else { clean.add(t); i++; }
        }
        return clean;
    }

    private static void applyRedirections(ProcessBuilder pb, Redirection redir) {
        if (redir.stdoutFile != null) {
            File f = new File(redir.stdoutFile);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            pb.redirectOutput(redir.appendStdout
                    ? ProcessBuilder.Redirect.appendTo(f)
                    : ProcessBuilder.Redirect.to(f));
        }
        if (redir.stderrFile != null) {
            File f = new File(redir.stderrFile);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            pb.redirectError(redir.appendStderr
                    ? ProcessBuilder.Redirect.appendTo(f)
                    : ProcessBuilder.Redirect.to(f));
        }
    }

    private static void writeToFile(String path, boolean append, String content) throws Exception {
        File f = new File(path);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try (PrintStream ps = new PrintStream(new FileOutputStream(f, append))) {
            ps.print(content);
        }
    }

    private static void touchFile(String path, boolean append) throws Exception {
        if (path == null) return;
        File f = new File(path);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        new FileOutputStream(f, append).close();
    }

    // ── PATH lookup ──────────────────────────────────────────────────────────

    private static File findExecutable(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File file = new File(dir, cmd);
            if (file.exists() && file.canExecute()) return file;
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Set<String> collectMatches(String word) {
        Set<String> matches = new TreeSet<>();
        for (String b : builtins) {
            if (b.startsWith(word)) matches.add(b);
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File d = new File(dir);
                if (!d.isDirectory()) continue;
                File[] files = d.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (f.canExecute() && !f.isDirectory() && f.getName().startsWith(word)) {
                        matches.add(f.getName());
                    }
                }
            }
        }
        return matches;
    }

    private static String longestCommonPrefix(List<String> words) {
    if (words.isEmpty()) return "";

    String prefix = words.get(0);

    for (int i = 1; i < words.size(); i++) {
        while (!words.get(i).startsWith(prefix)) {
            prefix = prefix.substring(0, prefix.length() - 1);

            if (prefix.isEmpty()) {
                return "";
            }
        }
    }

    return prefix;
}
    // ── Tab completer ────────────────────────────────────────────────────────

    private static class ShellCompleter implements Completer {

        // tracks whether we already rang the bell for this exact word
        // so the second TAB shows the list instead of ringing again
        private String lastBelledWord = null;

        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            String word = line.word();
            int wordIndex = line.wordIndex(); // 0 = command name, >0 = argument

            // argument position → check for registered completer first, then filenames
            if (wordIndex > 0) {
                // get the command name (first token of the line)
                List<String> lineTokens = line.words();
                String cmdName = lineTokens.isEmpty() ? "" : lineTokens.get(0);

                if (completionSpecs.containsKey(cmdName)) {
                    // run the registered completer script and use its stdout as candidates
                    try {
                        // argv[1]=cmdName, argv[2]=word being completed, argv[3]=previous word
                        List<String> lineWords = line.words();
                        String currentWord  = word; // the partial text at cursor
                        String previousWord;

                        if (wordIndex == 1) {
                            previousWord = cmdName;
                        } else if (wordIndex >= 2) {
                            previousWord = lineWords.get(wordIndex - 1);
                        } else {
                            previousWord = "";
                        }
                        ProcessBuilder pb = new ProcessBuilder(
                                completionSpecs.get(cmdName),   // script path
                                cmdName,                         // argv[1]: command
                                currentWord,                     // argv[2]: word being completed
                                previousWord                     // argv[3]: preceding word
                        );
                        // COMP_LINE = full command line text, COMP_POINT = cursor byte index
                        String compLine = line.line();
                        pb.environment().put("COMP_LINE",  compLine);
                        pb.environment().put("COMP_POINT", String.valueOf(compLine.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
                        pb.redirectErrorStream(true);
                        Process proc = pb.start();
                        proc.waitFor();
                        String output = new String(proc.getInputStream().readAllBytes()).trim();
                        if (!output.isEmpty()) {

    List<String> cands = new ArrayList<>();

    for (String c : output.split("\n")) {
        c = c.trim();
        if (!c.isEmpty()) {
            cands.add(c);
        }
    }

    java.util.Collections.sort(cands);

    if (cands.size() == 1) {
        candidates.add(new Candidate(cands.get(0)));
        lastBelledWord = null;
        return;
    }

    String lcp = longestCommonPrefix(cands);

    if (lcp.length() > currentWord.length()) {

        candidates.add(
                new Candidate(
                        lcp,
                        lcp,
                        null,
                        null,
                        null,
                        null,
                        false
                )
        );

        lastBelledWord = null;
        return;
    }

    String cacheKey = "script:" + cmdName + ":" + word;

    if (!cacheKey.equals(lastBelledWord)) {

        ringBell(reader);
        lastBelledWord = cacheKey;

    } else {

        lastBelledWord = null;

        String matchLine = String.join("  ", cands);

        terminal.writer().print("\r\n" + matchLine + "\r\n");
        terminal.writer().flush();

        terminal.writer().print("$ " + line.line());
        terminal.writer().flush();

        for (String c : cands) {
            candidates.add(
                    new Candidate(
                            c,
                            c,
                            null,
                            null,
                            null,
                            null,
                            false
                    )
            );
        }
    }
}
                        return;
                    } catch (Exception ignored) {
                        // completer script failed — fall through to filename completion
                    }
                    return;
                }

                // split "path/to/f" into dirPart="path/to/" and prefix="f"
                int lastSlash = word.lastIndexOf('/');
                String dirPart  = lastSlash >= 0 ? word.substring(0, lastSlash + 1) : "";
                String prefix   = lastSlash >= 0 ? word.substring(lastSlash + 1)    : word;

                // resolve the search directory against currentDir
                File searchDir = dirPart.isEmpty()
                        ? new File(currentDir)
                        : new File(currentDir, dirPart);

                Set<String> fileMatches = new TreeSet<>();
                File[] files = searchDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(prefix)) {
                            // rebuild the full token: dirPart + matched name (+ / for dirs)
                            String completed = dirPart + f.getName()
                                    + (f.isDirectory() ? "/" : "");
                            fileMatches.add(completed);
                        }
                    }
                }

                if (fileMatches.isEmpty()) {
                    ringBell(reader);
                    return;
                }
                if (fileMatches.size() == 1) {
                    String match = fileMatches.iterator().next();
                    if (match.endsWith("/")) {
                        // directory → trailing slash, NO trailing space (complete=false)
                        candidates.add(new Candidate(match, match, null, null, null, null, false));
                    } else {
                        // file → complete with trailing space (default behaviour)
                        candidates.add(new Candidate(match));
                    }
                    return;
                }
                // multiple matches → try LCP extension first
                String lcp = longestCommonPrefix(new ArrayList<>(fileMatches));
                if (lcp.length() > word.length()) {
                    // can extend to common prefix — do so without trailing space
                    candidates.add(new Candidate(lcp, lcp, null, null, null, null, false));
                    lastBelledWord = null;
                    return;
                }
                // already at LCP — first TAB rings bell, second TAB lists all matches
                if (!word.equals(lastBelledWord)) {
                    ringBell(reader);
                    lastBelledWord = word;
                } else {
                    lastBelledWord = null;
                    // print sorted matches on a new line, then reprint prompt + input
                    String matchLine = String.join("  ", fileMatches);
                    terminal.writer().print("\r\n" + matchLine + "\r\n");
                    terminal.writer().flush();
                    terminal.writer().print("$ " + line.line());
                    terminal.writer().flush();
                    // add candidates so JLine redraws the input line cleanly
                    for (String m : fileMatches) candidates.add(new Candidate(m, m, null, null, null, null, false));
                }
                return;
            }

            // wordIndex == 0 → complete command names
            Set<String> matches = collectMatches(word);

            if (matches.isEmpty()) {
                // no matches at all → bell, leave input unchanged
                ringBell(reader);
                lastBelledWord = null;
                return;
            }

            if (matches.size() == 1) {
                // unique match → complete it
                candidates.add(new Candidate(matches.iterator().next()));
                lastBelledWord = null;
                return;
            }

            // multiple matches
            String lcp = longestCommonPrefix(new ArrayList<>(matches));

            if (lcp.length() > word.length()) {
                // can extend to the common prefix — do that first
                // complete=false suppresses the trailing space JLine would add,
                // since this LCP isn't necessarily a complete/unique command name
                candidates.add(new Candidate(
                        lcp,        // value inserted into the line
                        lcp,        // display string
                        null,       // group
                        null,       // description
                        null,       // suffix  (null = no trailing space)
                        null,       // key
                        false       // complete — false = don't add trailing space
                ));
                lastBelledWord = null;
                return;
            }

            // already at the LCP — first TAB rings bell, second TAB shows list
            if (!word.equals(lastBelledWord)) {
                // first time here for this word: ring bell
                ringBell(reader);
                lastBelledWord = word;
            } else {
                // second TAB: print matches, then reprint prompt + current input
                lastBelledWord = null;
                String matchLine = String.join("  ", matches);

                // print the match list on a new line
                terminal.writer().print("\r\n" + matchLine + "\r\n");
                terminal.writer().flush();

                // reprint the prompt and the partial input the user had typed
                terminal.writer().print("$ " + word);
                terminal.writer().flush();

                // add candidates so JLine redraws the line properly after
                for (String m : matches) candidates.add(new Candidate(m));
            }
        }

        private void ringBell(LineReader reader) {
            // stdout so the tester sees \x07 in the raw stream
            System.out.print("\007");
            System.out.flush();
            reader.getTerminal().writer().print("\007");
            reader.getTerminal().writer().flush();
        }
    }

    // ── Main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new ShellCompleter())
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.AUTO_LIST, false)
                .option(LineReader.Option.AUTO_MENU, false)
                .build();

        while (true) {
            String line;
            try {
                line = reader.readLine("$ ");
            } catch (org.jline.reader.EndOfFileException | org.jline.reader.UserInterruptException e) {
                break;
            }
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            List<String> tokens = tokenize(line);
            if (tokens.isEmpty()) continue;

            Redirection redir = new Redirection();
            tokens = extractRedirections(tokens, redir);
            if (tokens.isEmpty()) continue;

            String command = tokens.get(0);

            // --- exit ---
            if (command.equals("exit")) {
                int code = 0;
                if (tokens.size() > 1) {
                    try { code = Integer.parseInt(tokens.get(1)); }
                    catch (NumberFormatException ignored) {}
                }
                System.exit(code);
            }

            // --- pwd ---
            if (command.equals("pwd")) {
                touchFile(redir.stderrFile, redir.appendStderr);
                String out = currentDir + "\n";
                if (redir.stdoutFile != null) writeToFile(redir.stdoutFile, redir.appendStdout, out);
                else System.out.print(out);
                continue;
            }

            // --- cd ---
            if (command.equals("cd")) {
                touchFile(redir.stdoutFile, redir.appendStdout);
                String path = tokens.size() > 1 ? tokens.get(1) : "~";
                if (path.equals("~")) path = System.getenv("HOME");
                File target = path.startsWith("/")
                        ? new File(path)
                        : new File(currentDir, path);
                if (target.exists() && target.isDirectory()) {
                    currentDir = target.getCanonicalPath();
                    touchFile(redir.stderrFile, redir.appendStderr);
                } else {
                    String err = "cd: " + path + ": No such file or directory\n";
                    if (redir.stderrFile != null) writeToFile(redir.stderrFile, redir.appendStderr, err);
                    else System.err.print(err);
                }
                continue;
            }

            // --- echo ---
            if (command.equals("echo")) {
                touchFile(redir.stderrFile, redir.appendStderr);
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < tokens.size(); i++) {
                    if (i > 1) sb.append(" ");
                    sb.append(tokens.get(i));
                }
                sb.append("\n");
                if (redir.stdoutFile != null) writeToFile(redir.stdoutFile, redir.appendStdout, sb.toString());
                else System.out.print(sb.toString());
                continue;
            }

            // --- type ---
            if (command.equals("type")) {
                touchFile(redir.stderrFile, redir.appendStderr);
                if (tokens.size() < 2) continue;
                String cmd = tokens.get(1);
                String result;
                if (builtins.contains(cmd)) {
                    result = cmd + " is a shell builtin\n";
                } else {
                    File executable = findExecutable(cmd);
                    result = executable != null
                            ? cmd + " is " + executable.getAbsolutePath() + "\n"
                            : cmd + ": not found\n";
                }
                if (redir.stdoutFile != null) writeToFile(redir.stdoutFile, redir.appendStdout, result);
                else System.out.print(result);
                continue;
            }

            // --- complete ---
            if (command.equals("complete")) {
                if (tokens.size() >= 4 && tokens.get(1).equals("-C")) {
                    // complete -C <script> <cmdName>  → register
                    String script  = tokens.get(2);
                    String cmdName = tokens.get(3);
                    completionSpecs.put(cmdName, script);
                } else if (tokens.size() >= 3 && tokens.get(1).equals("-r")) {
                    // complete -r <cmdName> → unregister (silent, even if not registered)
                    completionSpecs.remove(tokens.get(2));
                } else if (tokens.size() >= 3 && tokens.get(1).equals("-p")) {
                    // complete -p <cmdName>  → print registration or error
                    String cmdName = tokens.get(2);
                    String out;
                    if (completionSpecs.containsKey(cmdName)) {
                        out = "complete -C '" + completionSpecs.get(cmdName) + "' " + cmdName + "\n";
                        if (redir.stdoutFile != null) writeToFile(redir.stdoutFile, redir.appendStdout, out);
                        else { System.out.print(out); System.out.flush(); }
                    } else {
                        out = "complete: " + cmdName + ": no completion specification\n";
                        if (redir.stderrFile != null) writeToFile(redir.stderrFile, redir.appendStderr, out);
                        else { System.out.print(out); System.out.flush(); }
                    }
                }
                continue;
            }

            // --- external commands ---
            File executable = findExecutable(command);
            if (executable != null) {
                ProcessBuilder pb = new ProcessBuilder(tokens);
                pb.directory(new File(currentDir));
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                if (redir.stdoutFile == null) pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                if (redir.stderrFile == null)  pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                applyRedirections(pb, redir);
                Process process = pb.start();
                process.waitFor();
                continue;
            }

            System.out.println(command + ": command not found");
        }
    }
}