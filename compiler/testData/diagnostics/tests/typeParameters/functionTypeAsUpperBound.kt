fun <T: (Int) -> String> foo() {}

class A<T, U, V> where T : () -> Unit, U : (Int) -> Double, V : (T, U) -> U

interface B<T, U : (T) -> Unit>