import java.io.File;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class Main {

    private static final Set<String> builtins = new HashSet<>(
            Set.of("echo", "exit", "type", "pwd", "cd"));

    private static String currentDir = System.getProperty("user.dir"); 
    
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
            String command = sc.nextLine().trim();

            if (command.isEmpty()) continue;

            // --- exit --
            if (command.equals("exit") || command.startsWith("exit ")) {
                int code = 0;
                String[] parts = command.split(" ", 2);
                if (parts.length == 2) {
                    try { code = Integer.parseInt(parts[1].trim()); }
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
            if (command.equals("cd") || command.startsWith("cd ")) {
                String path = command.length() > 3 ? command.substring(3).trim() : "~";

                if (path.equals("~")) {
                    path = System.getenv("HOME");
                }

                File target = path.startsWith("/") ? new File(path) : new File(currentDir, path);

                if (target.exists() && target.isDirectory()) {
                    currentDir = target.getCanonicalPath();
                }
                else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }
                continue;
            }

            // --- echo ---
            if (command.startsWith("echo ") || command.equals("echo")) {
                String output = command.length() > 5 ? command.substring(5) : "";
                System.out.println(output);
                continue;
            }

            // --- type ---
            if (command.startsWith("type ") || command.equals("type")) {
                if (command.equals("type")) continue;
                String cmd = command.substring(5).trim();
                if (builtins.contains(cmd)) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    File executable = findExecutable(cmd);
                    if (executable != null) {
                        System.out.println(cmd + " is " + executable.getAbsolutePath());
                    } else {
                        System.out.println(cmd + ": not found");
                    }
                }
                continue;
            }

            // --- external commands ---
            String[] parts = command.split(" ");
            File executable = findExecutable(parts[0]);
            if (executable != null) {
                ProcessBuilder pb = new ProcessBuilder(parts);
                pb.directory(new File(currentDir));
                pb.inheritIO();
                Process process = pb.start();
                process.waitFor();
                continue;
            }

            System.out.println(parts[0] + ": command not found");
        }

        sc.close();
    }
}