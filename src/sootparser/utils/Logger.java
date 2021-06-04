package sootparser.utils;


/**
 * Outputs messages to standard/error outputs logging the calling method.
 * @author juniocezar
 */
public class Logger {
    /**
     * Represents stack depth according to caller.
     */
    private static int pos = 0;
    /**
     * Indicates if the logger is being called for the fist time.
     */
    private static boolean first = true;

    /**
     * Identifies the operating system being used to execute this code.
     */
    final static String OS = System.getProperty("os.name").toLowerCase();
    static boolean isUnix = false;

    // unix terminal colors
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BLACK = "\u001B[30m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_PURPLE = "\u001B[35m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_WHITE = "\u001B[37m";

    /**
     * Prints message to standard output
     * @param msg Text to print
     */
    public static void show(String msg) {
        if (first) {
            System.out.println("\n================ Initializing Tool ================\n");
            first = false;
            if (OS.contains("nix") || OS.contains("nux") || OS.contains("aix")) {
                isUnix = true;
            }
        }

        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String callerClass  = stackTraceElements[2+pos].getClassName();
        String callerMethod = stackTraceElements[2+pos].getMethodName();

        if (isUnix) {
            System.out.println(ANSI_YELLOW + callerClass + "." + callerMethod +
                ANSI_RED + " - " + ANSI_WHITE + msg + ANSI_RESET);
        } else {
            System.out.println(callerClass + "." + callerMethod + " - " + msg);
        }
    }
    /**
     * Prints message to standard output
     * @param msg Text to print
     */
    public static void log(String msg) {
        pos = 1;
        show(msg);
        pos = 0;
    }

}