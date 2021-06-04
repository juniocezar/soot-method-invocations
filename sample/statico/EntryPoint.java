package statico;

public class EntryPoint {  
    public static void main(String args[]) {  
       function_1();
       function_2();
       function_3();

       statico.Imported i = new statico.Imported();
       i.function_A();
       while (args == null) {
        i.function_B();
       }
       i.function_C();

    }  


    public static void function_1() {
        System.out.println("Hello Java");
    }

    public static void function_2() {
        for (int i = 0;;i++) {
            System.out.println("Hello Java");
        }        
    }

    public static void function_3() {
        for (int i = 0; i < 500;i++) {
            for (int j = 0; j < 1000;j++) {
                System.out.println("Hello Java");
            }        
        }        
    }
 }  