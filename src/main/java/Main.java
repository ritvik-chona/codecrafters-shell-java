import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {

    private static final Set<String> builtins = new HashSet<>(
            Set.of("echo", "exit", "type", "pwd", "cd"));

    private static String currentDir = System.getProperty("user.dir");
    
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

            } 
            else if (c == '\'') {
                i++;
                while (i < line.length() && line.charAt(i) != '\'') {
                    current.append(line.charAt(i));
                    i++;
                }
                i++;

            } 
            else if (c == '"') {
                i++;
                while (i < line.length() && line.charAt(i) != '"') {
                    current.append(line.charAt(i));
                    i++;
                }
                i++;

            } 
            else if (c == ' ' || c == '\t') {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                i++;

            }
            else {
                current.append(c);
                i++;
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
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
                System.out.println(currentDir);
                continue;
            }

            // --- cd ---
            if (command.equals("cd")) {
                String path = tokens.size() > 1 ? tokens.get(1) : "~";
                if (path.equals("~")) {
                    path = System.getenv("HOME");
                }
                File target = path.startsWith("/")
                        ? new File(path)
                        : new File(currentDir, path);

                if (target.exists() && target.isDirectory()) {
                    currentDir = target.getCanonicalPath();
                } 
                else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }
                continue;
            }

            // --- echo ---
            if (command.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < tokens.size(); i++) {
                    if (i > 1) sb.append(" ");
                    sb.append(tokens.get(i));
                }
                System.out.println(sb.toString());
                continue;
            }

            // --- type ---
            if (command.equals("type")) {
                if (tokens.size() < 2) continue;
                String cmd = tokens.get(1);
                if (builtins.contains(cmd)) {
                    System.out.println(cmd + " is a shell builtin");
                }
                else {
                    File executable = findExecutable(cmd);
                    if (executable != null) {
                        System.out.println(cmd + " is " + executable.getAbsolutePath());
                    }
                    else {
                        System.out.println(cmd + ": not found");
                    }
                }
                continue;
            }

            // --- external commands ---
            File executable = findExecutable(command);
            if (executable != null) {
                ProcessBuilder pb = new ProcessBuilder(tokens);
                pb.directory(new File(currentDir));
                pb.inheritIO();
                Process process = pb.start();
                process.waitFor();
                continue;
            }
            System.out.println(command + ": command not found");
        }
        sc.close();
    }
}