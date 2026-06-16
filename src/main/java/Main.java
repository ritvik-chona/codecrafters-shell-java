import java.io.File;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class Main {

    private static final Set<String> builtins = new HashSet<>(
            Set.of("echo", "exit", "type")
    );

    private static File findExecutable(String cmd) {
        String[] paths = System.getenv("PATH").split(File.pathSeparator);

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

            String command = sc.nextLine();

            if (command.equals("exit")) {
                break;
            }

            if (command.startsWith("echo ")) {
                System.out.println(command.substring(5));
                continue;
            }

            if (command.startsWith("type ")) {
                String cmd = command.substring(5);

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

            String[] parts = command.split(" ");

File executable = findExecutable(parts[0]);

if (executable != null) {

    ProcessBuilder pb = new ProcessBuilder(parts);

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