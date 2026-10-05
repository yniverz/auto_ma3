package automa3.show;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Expected values printed by Python 3 (round, repr, format(v, "g")). */
class PyTest {

    @Test
    void roundHalfToEvenOnTheExactValue() {
        assertEquals(2, Py.roundInt(2.5));
        assertEquals(4, Py.roundInt(3.5));
        assertEquals(-2, Py.roundInt(-2.5));
        assertEquals(2.67, Py.round(2.675, 2)); // 2.675 is 2.67499999... in binary
        assertEquals(1500.5, Py.round(1500.45, 1)); // 1500.45 is 1500.4500000000000455 in binary
        assertEquals(0.1, Py.round(0.05, 1)); // 0.05 is 0.05000000000000000277
    }

    @Test
    void reprLikePython() {
        assertEquals("1.0", Py.repr(1.0));
        assertEquals("0.1", Py.repr(0.1));
        assertEquals("1e-05", Py.repr(0.00001));
        assertEquals("0.0001", Py.repr(0.0001));
        assertEquals("1e+16", Py.repr(1e16));
        assertEquals("1234567890123456.0", Py.repr(1234567890123456.0));
        assertEquals("-12.25", Py.repr(-12.25));
        assertEquals("1500.5", Py.repr(1500.5));
        assertEquals("-0.0", Py.repr(-0.0));
    }

    @Test
    void gLikePython() {
        assertEquals("1.5", Py.g(1.5));
        assertEquals("0.4", Py.g(0.4));
        assertEquals("100", Py.g(100.0));
        assertEquals("1e+06", Py.g(1e6));
        assertEquals("123457", Py.g(123456.7));
        assertEquals("1.23457e+06", Py.g(1234567.0));
        assertEquals("1e-05", Py.g(0.00001));
        assertEquals("0.0001", Py.g(0.0001));
        assertEquals("-12.25", Py.g(-12.25));
        assertEquals("0.333333", Py.g(1.0 / 3));
        assertEquals("270", Py.num(270.0));
        assertEquals("10.5", Py.num(10.5));
        assertEquals("2", Py.f0(2.5));
        assertEquals("4", Py.f0(3.5));
        assertEquals("-1200", Py.f0(-1200.4));
    }

    @Test
    void reprOfStrings() {
        assertEquals("'abc'", Py.reprString("abc"));
        assertEquals("\"it's\"", Py.reprString("it's"));
        assertEquals("'say \"hi\"'", Py.reprString("say \"hi\""));
        assertEquals("'a\\tb'", Py.reprString("a\tb"));
    }
}
