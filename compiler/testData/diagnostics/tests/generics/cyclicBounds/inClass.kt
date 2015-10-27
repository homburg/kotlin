class A1<T : F?, F : <!CYCLIC_GENERIC_UPPER_BOUND!>T?<!>>

class A2<T : F?, F : E, E : <!CYCLIC_GENERIC_UPPER_BOUND!>F?<!>>

class A3<T, F> where T : F?, F : <!CYCLIC_GENERIC_UPPER_BOUND!>T?<!>

class A4<T, F, E> where T : F?, F : E, E : <!CYCLIC_GENERIC_UPPER_BOUND!>F<!>