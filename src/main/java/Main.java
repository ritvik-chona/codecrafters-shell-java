import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {

    private static final Set<String> builtins = new HashSet<>(
            Set.of("echo", "exit", "type", "pwd", "cd"));

    private static String currentDir = System.getProperty("user.dir");

    private static class Redirection {
        String stdoutFile  = null;
        String stderrFile  = null;
        boolean appendStdout = false;
        boolean appendStderr = false;
    }

    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;

        while (i < line.length()) {
            char c = line.charAt(i);

            if (c == '\\') {
                i++;
                if (i < line.length()) {
                    current.append(line.charAt(i));
                    i++;
                }
            } else if (c == '\'') {
                i++;
                while (i < line.length() && line.charAt(i) != '\'') {
                    current.append(line.charAt(i));
                    i++;
                }
                i++;
            } else if (c == '"') {
                i++;
                while (i < line.length() && line.charAt(i) != '"') {
                    char d = line.charAt(i);
                    if (d == '\\' && i + 1 < line.length()) {
                        char next = line.charAt(i + 1);
                        if (next == '\\' || next == '"' || next == '$' || next == '`') {
                            current.append(next);
                            i += 2;
                        } else {
                            current.append(d);
                            i++;
                        }
                    } else {
                        current.append(d);
                        i++;
                    }
                }
                i++;
            } else if (c == ' ' || c == '\t') {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                i++;
            } else {
                current.append(c);
                i++;
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static List<String> extractRedirections(List<String> tokens, Redirection redir) {
        List<String> clean = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if ((t.equals(">") || t.equals("1>")) && i + 1 < tokens.size()) {
                redir.stdoutFile = tokens.get(i + 1);
                redir.appendStdout = false;
                i += 2;
            } else if ((t.equals(">>") || t.equals("1>>")) && i + 1 < tokens.size()) {
                redir.stdoutFile = tokens.get(i + 1);
                redir.appendStdout = true;
                i += 2;
            } else if (t.equals("2>") && i + 1 < tokens.size()) {
                redir.stderrFile = tokens.get(i + 1);
                redir.appendStderr = false;
                i += 2;
            } else if (t.equals("2>>") && i + 1 < tokens.size()) {
                redir.stderrFile = tokens.get(i + 1);
                redir.appendStderr = true;
                i += 2;
            } else {
                clean.add(t);
                i++;
            }
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

    // Write string to a file (truncate or append). Always creates the file.
    private static void writeToFile(String path, boolean append, String content) throws Exception {
        File f = new File(path);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try (PrintStream ps = new PrintStream(new FileOutputStream(f, append))) {
            ps.print(content);
        }
    }

    // Ensure a redirection file exists even if nothing is written to it.
    // Shells always create the target file when a redirection is present.
    private static void touchFile(String path, boolean append) throws Exception {
        if (path == null) return;
        File f = new File(path);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        // opening with append=false truncates; append=true leaves existing content
        new FileOutputStream(f, append).close();
    }

    private static File findExecutable(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] paths = pathEnv.split(File.pathSeparator);
        for (String dir : paths) {
            File file = new File(dir, cmd);
            if (file.exists() && file.canExecute()) {
                return file;
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().trim();
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
                touchFile(redir.stderrFile, redir.appendStderr); // create stderr file even if unused
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
                // echo never writes to stderr — always create the file empty if redirected
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

        sc.close();
    }
}