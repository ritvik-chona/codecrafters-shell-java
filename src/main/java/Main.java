import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class Main {

    private static Set<String> builtins = new HashSet<>(
        Set.of("echo", "exit", "type"));
    public static void main(String[] args) throws Exception {
        
        Scanner sc = new Scanner(System.in);
        while(true){
            System.out.print("$ ");
            String command = sc.nextLine();

            if(command.equals("exit")) break;

            if (command.startsWith("echo ")) {
                System.out.println(command.substring(5));
                continue;
            }

            if (command.startsWith("type ")) {
                String cmd = command.substring(5);

                if (builtins.contains(cmd)) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    System.out.println(cmd + ": not found");
                }
                continue;
            }
            System.out.println(command + ": command not found");
        }
    }
}
