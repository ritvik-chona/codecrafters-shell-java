import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Completer;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class Main {

    private static final Set<String> builtins = new HashSet<>(
            Set.of("echo", "exit", "type", "pwd", "cd", "complete", "jobs"));

    private static String currentDir = System.getProperty("user.dir");

    private static final Map<String, String> completionSpecs = new HashMap<>();

    // background job tracking — three parallel lists
    private static final List<long[]>   bgJobs      = new ArrayList<>(); // [jobNum, pid]
    private static final List<Process>  bgProcesses = new ArrayList<>();
    private static final List<String>   bgCommands  = new ArrayList<>();

    private static Terminal terminal;

    // ── Redirection container ─────────────────────────────────────────────────

    private static class Redirection {
        String  stdoutFile   = null;
        String  stderrFile   = null;
        boolean appendStdout = false;
        boolean appendStderr = false;
    }

    // ── Tokenizer ─────────────────────────────────────────────────────────────

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

    // ── Redirection helpers ───────────────────────────────────────────────────

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

    // ── PATH lookup ───────────────────────────────────────────────────────────

    private static File findExecutable(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File file = new File(dir, cmd);
            if (file.exists() && file.canExecute()) return file;
        }
        return null;
    }

    // ── Tab completion helpers ────────────────────────────────────────────────

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
                    if (f.canExecute() && !f.isDirectory() && f.getName().startsWith(word))
                        matches.add(f.getName());
                }
            }
        }
        return matches;
    }

    private static String longestCommonPrefix(List<String> words) {
        if (words.isEmpty()) return "";
        // strip trailing '/' before comparing
        List<String> stripped = words.stream()
                .map(w -> w.endsWith("/") ? w.substring(0, w.length() - 1) : w)
                .collect(Collectors.toList());
        String first = stripped.get(0);
        int len = first.length();
        for (int i = 1; i < stripped.size(); i++) {
            len = Math.min(len, stripped.get(i).length());
            for (int j = 0; j < len; j++) {
                if (first.charAt(j) != stripped.get(i).charAt(j)) { len = j; break; }
            }
        }
        return first.substring(0, len);
    }

    // ── Tab completer ─────────────────────────────────────────────────────────

    private static class ShellCompleter implements Completer {

        private String lastBelledWord = null;

        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            String word      = line.word();
            int    wordIndex = line.wordIndex();

            // ── argument position ──────────────────────────────────────────
            if (wordIndex > 0) {
                List<String> lineWords = line.words();
                String cmdName = lineWords.isEmpty() ? "" : lineWords.get(0);

                // registered completer script?
                if (completionSpecs.containsKey(cmdName)) {
                    try {
                        String currentWord  = word;
                        String previousWord;
                        if (wordIndex == 1)       previousWord = cmdName;
                        else if (wordIndex >= 2)  previousWord = lineWords.get(wordIndex - 1);
                        else                      previousWord = "";

                        ProcessBuilder pb = new ProcessBuilder(
                                completionSpecs.get(cmdName),
                                cmdName,
                                currentWord,
                                previousWord);
                        String compLine = line.line();
                        pb.environment().put("COMP_LINE",  compLine);
                        pb.environment().put("COMP_POINT",
                                String.valueOf(compLine.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
                        pb.redirectErrorStream(true);
                        Process proc = pb.start();
                        proc.waitFor();
                        String output = new String(proc.getInputStream().readAllBytes()).trim();

                        if (!output.isEmpty()) {
                            List<String> cands = new ArrayList<>();
                            for (String c : output.split("\n")) {
                                c = c.trim();
                                if (!c.isEmpty()) cands.add(c);
                            }
                            Collections.sort(cands);

                            if (cands.size() == 1) {
                                candidates.add(new Candidate(cands.get(0)));
                                lastBelledWord = null;
                                return;
                            }

                            String lcp = longestCommonPrefix(cands);
                            if (lcp.length() > currentWord.length()) {
                                candidates.add(new Candidate(lcp, lcp, null, null, null, null, false));
                                lastBelledWord = null;
                                return;
                            }

                            String cacheKey = "script:" + cmdName + ":" + word;
                            if (!cacheKey.equals(lastBelledWord)) {
                                ringBell(reader);
                                lastBelledWord = cacheKey;
                            } else {
                                lastBelledWord = null;
                                terminal.writer().print("\r\n" + String.join("  ", cands) + "\r\n");
                                terminal.writer().flush();
                                terminal.writer().print("$ " + line.line());
                                terminal.writer().flush();
                                for (String c : cands)
                                    candidates.add(new Candidate(c, c, null, null, null, null, false));
                            }
                        } else {
                            ringBell(reader);
                        }
                    } catch (Exception ignored) {
                        ringBell(reader);
                    }
                    return;
                }

                // ── filename completion ────────────────────────────────────
                int    lastSlash = word.lastIndexOf('/');
                String dirPart   = lastSlash >= 0 ? word.substring(0, lastSlash + 1) : "";
                String prefix    = lastSlash >= 0 ? word.substring(lastSlash + 1)    : word;

                File searchDir = dirPart.isEmpty()
                        ? new File(currentDir)
                        : new File(currentDir, dirPart);

                Set<String> fileMatches = new TreeSet<>();
                File[] files = searchDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(prefix)) {
                            fileMatches.add(dirPart + f.getName() + (f.isDirectory() ? "/" : ""));
                        }
                    }
                }

                if (fileMatches.isEmpty()) { ringBell(reader); return; }

                if (fileMatches.size() == 1) {
                    String match = fileMatches.iterator().next();
                    if (match.endsWith("/"))
                        candidates.add(new Candidate(match, match, null, null, null, null, false));
                    else
                        candidates.add(new Candidate(match));
                    return;
                }

                String lcp = longestCommonPrefix(new ArrayList<>(fileMatches));
                if (lcp.length() > word.length()) {
                    candidates.add(new Candidate(lcp, lcp, null, null, null, null, false));
                    lastBelledWord = null;
                    return;
                }
                if (!word.equals(lastBelledWord)) {
                    ringBell(reader);
                    lastBelledWord = word;
                } else {
                    lastBelledWord = null;
                    terminal.writer().print("\r\n" + String.join("  ", fileMatches) + "\r\n");
                    terminal.writer().flush();
                    terminal.writer().print("$ " + line.line());
                    terminal.writer().flush();
                    for (String m : fileMatches)
                        candidates.add(new Candidate(m, m, null, null, null, null, false));
                }
                return;
            }

            // ── command-name completion (wordIndex == 0) ───────────────────
            Set<String> matches = collectMatches(word);

            if (matches.isEmpty()) { ringBell(reader); lastBelledWord = null; return; }

            if (matches.size() == 1) {
                candidates.add(new Candidate(matches.iterator().next()));
                lastBelledWord = null;
                return;
            }

            String lcp = longestCommonPrefix(new ArrayList<>(matches));
            if (lcp.length() > word.length()) {
                candidates.add(new Candidate(lcp, lcp, null, null, null, null, false));
                lastBelledWord = null;
                return;
            }

            if (!word.equals(lastBelledWord)) {
                ringBell(reader);
                lastBelledWord = word;
            } else {
                lastBelledWord = null;
                terminal.writer().print("\r\n" + String.join("  ", matches) + "\r\n");
                terminal.writer().flush();
                terminal.writer().print("$ " + word);
                terminal.writer().flush();
                for (String m : matches) candidates.add(new Candidate(m));
            }
        }

        private void ringBell(LineReader reader) {
            System.out.print("\007");
            System.out.flush();
            reader.getTerminal().writer().print("\007");
            reader.getTerminal().writer().flush();
        }
    }

    // ── Background job management ─────────────────────────────────────────────

    private static String checkJobs(boolean printAll) {
        int lastIdx       = bgJobs.size() - 1;
        int secondLastIdx = bgJobs.size() - 2;
        List<Integer> toRemove = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        for (int j = 0; j < bgJobs.size(); j++) {
            Process proc   = bgProcesses.get(j);
            int     jobNum = (int) bgJobs.get(j)[0];
            String  cmd    = bgCommands.get(j);
            char    marker = j == lastIdx ? '+' : (j == secondLastIdx ? '-' : ' ');

            if (proc.isAlive()) {
                if (printAll)
                    sb.append(String.format("[%d]%c  %-24s%s &%n", jobNum, marker, "Running", cmd));
            } else {
                sb.append(String.format("[%d]%c  %-24s%s%n", jobNum, marker, "Done", cmd));
                toRemove.add(j);
            }
        }
        for (int i = toRemove.size() - 1; i >= 0; i--) {
            int idx = toRemove.get(i);
            bgJobs.remove(idx);
            bgProcesses.remove(idx);
            bgCommands.remove(idx);
        }
        return sb.toString();
    }

    // ── Builtin executor ──────────────────────────────────────────────────────

    private static byte[] executeBuiltin(String command, List<String> tokens, Redirection redir) throws Exception {
        switch (command) {
            case "exit": {
                int code = 0;
                if (tokens.size() > 1) {
                    try { code = Integer.parseInt(tokens.get(1)); } catch (NumberFormatException ignored) {}
                }
                System.exit(code);
            }
            case "pwd": {
                touchFile(redir.stderrFile, redir.appendStderr);
                String out = currentDir + "\n";
                if (redir.stdoutFile != null) { writeToFile(redir.stdoutFile, redir.appendStdout, out); return new byte[0]; }
                return out.getBytes();
            }
            case "cd": {
                touchFile(redir.stdoutFile, redir.appendStdout);
                String path = tokens.size() > 1 ? tokens.get(1) : "~";
                if (path.equals("~")) path = System.getenv("HOME");
                File target = path.startsWith("/") ? new File(path) : new File(currentDir, path);
                if (target.exists() && target.isDirectory()) {
                    currentDir = target.getCanonicalPath();
                    touchFile(redir.stderrFile, redir.appendStderr);
                } else {
                    String err = "cd: " + path + ": No such file or directory\n";
                    if (redir.stderrFile != null) writeToFile(redir.stderrFile, redir.appendStderr, err);
                    else System.err.print(err);
                }
                return new byte[0];
            }
            case "echo": {
                touchFile(redir.stderrFile, redir.appendStderr);
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < tokens.size(); i++) {
                    if (i > 1) sb.append(" ");
                    sb.append(tokens.get(i));
                }
                sb.append("\n");
                if (redir.stdoutFile != null) { writeToFile(redir.stdoutFile, redir.appendStdout, sb.toString()); return new byte[0]; }
                return sb.toString().getBytes();
            }
            case "type": {
                touchFile(redir.stderrFile, redir.appendStderr);
                if (tokens.size() < 2) return new byte[0];
                String cmd = tokens.get(1);
                String result = builtins.contains(cmd)
                        ? cmd + " is a shell builtin\n"
                        : (findExecutable(cmd) != null
                                ? cmd + " is " + findExecutable(cmd).getAbsolutePath() + "\n"
                                : cmd + ": not found\n");
                if (redir.stdoutFile != null) { writeToFile(redir.stdoutFile, redir.appendStdout, result); return new byte[0]; }
                return result.getBytes();
            }
            case "jobs": {
                String result = checkJobs(true);
                if (redir.stdoutFile != null) { writeToFile(redir.stdoutFile, redir.appendStdout, result); return new byte[0]; }
                return result.getBytes();
            }
            case "complete": {
                if (tokens.size() >= 4 && tokens.get(1).equals("-C")) {
                    completionSpecs.put(tokens.get(3), tokens.get(2));
                } else if (tokens.size() >= 3 && tokens.get(1).equals("-r")) {
                    completionSpecs.remove(tokens.get(2));
                } else if (tokens.size() >= 3 && tokens.get(1).equals("-p")) {
                    String cName = tokens.get(2);
                    if (completionSpecs.containsKey(cName)) {
                        String out = "complete -C '" + completionSpecs.get(cName) + "' " + cName + "\n";
                        if (redir.stdoutFile != null) { writeToFile(redir.stdoutFile, redir.appendStdout, out); return new byte[0]; }
                        return out.getBytes();
                    } else {
                        String err = "complete: " + cName + ": no completion specification\n";
                        if (redir.stderrFile != null) writeToFile(redir.stderrFile, redir.appendStderr, err);
                        else { System.out.print(err); System.out.flush(); }
                    }
                }
                return new byte[0];
            }
        }
        return new byte[0];
    }

    // ── Pipeline execution ────────────────────────────────────────────────────

    /**
     * Splits a flat token list on "|" and returns a list of per-stage token lists.
     */
    private static List<List<String>> splitPipeline(List<String> tokens) {
        List<List<String>> stages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String t : tokens) {
            if (t.equals("|")) {
                if (!current.isEmpty()) { stages.add(current); current = new ArrayList<>(); }
            } else {
                current.add(t);
            }
        }
        if (!current.isEmpty()) stages.add(current);
        return stages;
    }

    /**
     * Executes a pipeline. Handles:
     *   - single command (no pipe)
     *   - multi-command pipeline using ProcessBuilder.startPipeline
     *   - builtins anywhere in the pipeline (feeds their bytes via pipe)
     *   - background execution
     */
    private static void executePipeline(List<List<String>> stages, boolean background) throws Exception {
        if (stages.isEmpty()) return;

        // ── single command (most common case) ────────────────────────────
        if (stages.size() == 1) {
            List<String> stageTokens = stages.get(0);
            Redirection redir = new Redirection();
            stageTokens = extractRedirections(stageTokens, redir);
            if (stageTokens.isEmpty()) return;
            String cmd = stageTokens.get(0);

            if (builtins.contains(cmd)) {
                byte[] out = executeBuiltin(cmd, stageTokens, redir);
                if (out.length > 0) { System.out.write(out); System.out.flush(); }
                return;
            }

            File exe = findExecutable(cmd);
            if (exe == null) { System.out.println(cmd + ": command not found"); return; }

            ProcessBuilder pb = new ProcessBuilder(stageTokens);
            pb.directory(new File(currentDir));
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            if (redir.stdoutFile == null) pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            if (redir.stderrFile == null) pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            applyRedirections(pb, redir);

            Process proc = pb.start();
            if (background) {
                int jobNum = nextJobNumber();
                bgJobs.add(new long[]{jobNum, proc.pid()});
                bgProcesses.add(proc);
                bgCommands.add(String.join(" ", stageTokens));
                System.out.println("[" + jobNum + "] " + proc.pid());
            } else {
                proc.waitFor();
            }
            return;
        }

        // ── multi-stage pipeline ─────────────────────────────────────────
        // Strategy: collect consecutive external commands into one startPipeline call.
        // When a builtin appears, materialise its output as bytes and feed it into
        // the stdin of the next external stage via the process's OutputStream.

        // First pass: resolve each stage (validate executables, separate builtins)
        List<Object> resolved = new ArrayList<>(); // String[] cmd  OR  byte[] builtin-output
        for (List<String> stage : stages) {
            Redirection r = new Redirection();
            List<String> clean = extractRedirections(stage, r);
            if (clean.isEmpty()) continue;
            String cmd = clean.get(0);
            if (builtins.contains(cmd)) {
                byte[] out = executeBuiltin(cmd, clean, r);
                resolved.add(out);
            } else {
                if (findExecutable(cmd) == null) {
                    System.out.println(cmd + ": command not found");
                    return;
                }
                // store as String[] with redirection info bundled — we'll rebuild PB below
                // use a small wrapper
                resolved.add(new StageInfo(clean, r));
            }
        }
        if (resolved.isEmpty()) return;

        // Second pass: execute, chaining output→input between stages
        // We run segments of consecutive external commands via startPipeline,
        // injecting builtin bytes as needed.
        byte[] pendingInput = null; // bytes to feed into the next stage's stdin

        int idx = 0;
        while (idx < resolved.size()) {
            Object item = resolved.get(idx);

            if (item instanceof byte[]) {
                byte[] builtinOut = (byte[]) item;
                // builtins don't consume stdin — discard any pending input from
                // a prior external stage (e.g. "ls | type exit": ls output is ignored)
                pendingInput = null;

                if (idx + 1 >= resolved.size()) {
                    // last stage is a builtin — print its output directly
                    System.out.write(builtinOut);
                    System.out.flush();
                } else {
                    // middle builtin — its output feeds the next stage
                    pendingInput = builtinOut;
                }
                idx++;
                continue;
            }

            // collect a run of external stages
            List<StageInfo> run = new ArrayList<>();
            while (idx < resolved.size() && resolved.get(idx) instanceof StageInfo) {
                run.add((StageInfo) resolved.get(idx));
                idx++;
            }

            boolean isLastRun = (idx >= resolved.size());

            List<ProcessBuilder> builders = new ArrayList<>();
            for (int k = 0; k < run.size(); k++) {
                StageInfo si = run.get(k);
                ProcessBuilder pb = new ProcessBuilder(si.tokens);
                pb.directory(new File(currentDir));

                // stdin: PIPE so we can feed pendingInput into first stage
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);

                // stdout: PIPE between stages; last stage → terminal (or file)
                boolean isLast = isLastRun && (k == run.size() - 1);
                if (!isLast) {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                } else {
                    if (si.redir.stdoutFile == null) pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
                if (si.redir.stderrFile == null) pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                applyRedirections(pb, si.redir);
                builders.add(pb);
            }

            List<Process> procs = ProcessBuilder.startPipeline(builders);

            // feed any pending builtin output into the first process's stdin
            if (pendingInput != null && !procs.isEmpty()) {
                try (OutputStream stdin = procs.get(0).getOutputStream()) {
                    stdin.write(pendingInput);
                }
                pendingInput = null;
            } else if (!procs.isEmpty()) {
                // close stdin of first proc if nothing to feed
                // (it inherits nothing since we set PIPE — close it so it gets EOF)
                procs.get(0).getOutputStream().close();
            }

            // if there are more stages after this run, capture this run's stdout
            if (!isLastRun) {
                Process last = procs.get(procs.size() - 1);
                for (int k = 0; k < procs.size() - 1; k++) procs.get(k).waitFor();
                pendingInput = last.getInputStream().readAllBytes();
                last.waitFor();
            } else {
                if (background) {
                    Process last = procs.get(procs.size() - 1);
                    int jobNum = nextJobNumber();
                    bgJobs.add(new long[]{jobNum, last.pid()});
                    bgProcesses.add(last);
                    bgCommands.add(stages.stream()
                            .map(s -> String.join(" ", s))
                            .collect(Collectors.joining(" | ")));
                    System.out.println("[" + jobNum + "] " + last.pid());
                } else {
                    for (Process p : procs) p.waitFor();
                }
            }
        }
    }

    private static int nextJobNumber() {
        int num = 1;
        for (long[] job : bgJobs) { if ((int)job[0] == num) num++; }
        return num;
    }

    // small holder so we can keep redirection alongside tokens
    private static class StageInfo {
        final List<String> tokens;
        final Redirection  redir;
        StageInfo(List<String> tokens, Redirection redir) {
            this.tokens = tokens;
            this.redir  = redir;
        }
    }

    // ── Main REPL ─────────────────────────────────────────────────────────────

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
                Thread.sleep(25);
                String doneJobs = checkJobs(false);
                if (!doneJobs.isEmpty()) System.out.print(doneJobs);
                line = reader.readLine("$ ");
            } catch (org.jline.reader.EndOfFileException | org.jline.reader.UserInterruptException e) {
                break;
            } catch (InterruptedException e) {
                break;
            }
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            List<String> tokens = tokenize(line);
            if (tokens.isEmpty()) continue;

            // detect trailing & for background
            boolean background = false;
            if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&")) {
                background = true;
                tokens.remove(tokens.size() - 1);
            }
            if (tokens.isEmpty()) continue;

            List<List<String>> stages = splitPipeline(tokens);
            executePipeline(stages, background);
        }
    }
}