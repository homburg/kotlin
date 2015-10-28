public class MyClass(val provider: (() -> String)?) {
    fun foo() {
        if(provider != null) {
            bar(provider())  
        }
    }

    fun bar(s : String) = s
}