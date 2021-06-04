package statico;

public class Imported {
    public static void function_A() {
        Integer a = new Integer("1");
        System.out.printf(Integer.toString(1000));
    }

    public static void function_B() {
        for (int i = 0;;i++) {
            System.out.println("Hello Java");
        }        
    }

    public static void function_C() {
        for (int i = 0; i < 500;i++) {
            for (int j = 0; j < 1000;j++) {
                System.out.println("Hello Java");
            }        
        }        
    }
 }  