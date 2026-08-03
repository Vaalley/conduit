package eu.mctraveler.sign;

/**
 * Source-markup access exposed by the sign mixin to the Kotlin rendering code.
 */
public interface SignSourceAccess {

    String mctraveler$getSource(boolean front, int line);

    void mctraveler$setSource(boolean front, int line, String source);
}
