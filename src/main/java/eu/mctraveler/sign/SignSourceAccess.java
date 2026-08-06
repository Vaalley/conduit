package eu.mctraveler.sign;

/**
 * Source-markup access exposed by the sign mixin to the Kotlin rendering code.
 */
public interface SignSourceAccess {

    String signSource(boolean front, int line);

    void setSignSource(boolean front, int line, String source);
}
