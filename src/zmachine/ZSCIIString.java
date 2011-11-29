/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package zmachine;

import java.util.Arrays;

/**
 *
 * @author katharine
 */
public class ZSCIIString {
    protected short[] chars;
    protected ZMachine machine;
    
    public static final char[] EXTRA_CHARACTERS = {
        'ä', 'ö', 'ü',
        'Ä', 'Ö', 'Ü',
        'ß', '«', '»',
        'ë', 'ï', 'ÿ',
        'Ë', 'Ï', 'á',
        'é', 'í', 'ó', 
        'ú', 'ý', 'Á',
        'É', 'Í', 'Ó',
        'Ú', 'Ý', 'à',
        'è', 'ì', 'ò',
        'ù', 'À', 'È',
        'Ì', 'Ò', 'Ù',
        'â', 'ê', 'î',
        'ô', 'û', 'Â',
        'Ê', 'Î', 'Ô',
        'Û', 'å', 'Å',
        'ø', 'Ø', 'ã',
        'ñ', 'õ', 'Ã',
        'Ñ', 'Õ', 'æ',
        'Æ', 'ç', 'Ç',
        'þ', 'ð', 'Þ',
        'Ð', '£', 'œ',
        'Œ', '¡', '¿',
    };
    
    public ZSCIIString(ZMachine machine, short[] chars) {
        this.chars = chars;
        this.machine = machine;
    }
    
    public ZSCIIString(ZMachine machine, String str) {
        this.machine = machine;
        this.chars = new short[str.length()];
        for(int i = 0; i < str.length(); ++i) {
            this.chars[i] = (short)str.charAt(i);
        }
    }
    
    public short[] toBytes() {
        return this.chars;
    }
    
    @Override
    public String toString() {
        StringBuilder str = new StringBuilder(this.chars.length);
        for(int i = 0; i < this.chars.length; ++i) {
            str.append(this.getCharAt(i));
        }
        return str.toString();
    }
    
    public ZString toZString(int bytes) throws ZError {
        if(bytes % 2 == 1) {
            throw new ZError("Attempt to create ZString with non-even size.");
        }
        // 3 ZChars per byte-pair -> 1.5n characters in n bytes.
        int max_chars = bytes / 2 * 3;
        byte[] zchars = new byte[max_chars];
        // i = position in this.chars
        // j = position in zchars
        // Fill zchars with the ZChar versions of this.chars, up to the limit.
        int i = 0, j = 0;
        for(; i < this.chars.length && j < max_chars; ++i) {
            short chr = this.chars[i];
            byte[] n = this.charToZChars(chr);
            if(n.length + j > max_chars) {
                break;
            }
            System.arraycopy(n, 0, zchars, j, n.length);
            j += n.length;
        }
        // If we didn't hit the character limit, pad it out with 5s (§3.7)
        while(j < max_chars) {
            zchars[j++] = 5;
        }

        return new ZString(this.machine, zchars);
    }

    // Converts a ZSCII (~ASCII) character to a sequence of one or two
    // ZChars.
    // TODO: Currently assumes version 2 or 3. Should accept version 1.
    protected byte[] charToZChars(short chr) {
        switch(chr) {
            case 'a': { byte[] r = { 6 }; return r; }
            case 'b': { byte[] r = { 7 }; return r; }
            case 'c': { byte[] r = { 8 }; return r; }
            case 'd': { byte[] r = { 9 }; return r; }
            case 'e': { byte[] r = { 10 }; return r; }
            case 'f': { byte[] r = { 11 }; return r; }
            case 'g': { byte[] r = { 12 }; return r; }
            case 'h': { byte[] r = { 13 }; return r; }
            case 'i': { byte[] r = { 14 }; return r; }
            case 'j': { byte[] r = { 15 }; return r; }
            case 'k': { byte[] r = { 16 }; return r; }
            case 'l': { byte[] r = { 17 }; return r; }
            case 'm': { byte[] r = { 18 }; return r; }
            case 'n': { byte[] r = { 19 }; return r; }
            case 'o': { byte[] r = { 20 }; return r; }
            case 'p': { byte[] r = { 21 }; return r; }
            case 'q': { byte[] r = { 22 }; return r; }
            case 'r': { byte[] r = { 23 }; return r; }
            case 's': { byte[] r = { 24 }; return r; }
            case 't': { byte[] r = { 25 }; return r; }
            case 'u': { byte[] r = { 26 }; return r; }
            case 'v': { byte[] r = { 27 }; return r; }
            case 'w': { byte[] r = { 28 }; return r; }
            case 'x': { byte[] r = { 29 }; return r; }
            case 'y': { byte[] r = { 30 }; return r; }
            case 'z': { byte[] r = { 31 }; return r; }
            case 'A': { byte[] r = { 4, 6 }; return r; }
            case 'B': { byte[] r = { 4, 7 }; return r; }
            case 'C': { byte[] r = { 4, 8 }; return r; }
            case 'D': { byte[] r = { 4, 9 }; return r; }
            case 'E': { byte[] r = { 4, 10 }; return r; }
            case 'F': { byte[] r = { 4, 11 }; return r; }
            case 'G': { byte[] r = { 4, 12 }; return r; }
            case 'H': { byte[] r = { 4, 13 }; return r; }
            case 'I': { byte[] r = { 4, 14 }; return r; }
            case 'J': { byte[] r = { 4, 15 }; return r; }
            case 'K': { byte[] r = { 4, 16 }; return r; }
            case 'L': { byte[] r = { 4, 17 }; return r; }
            case 'M': { byte[] r = { 4, 18 }; return r; }
            case 'N': { byte[] r = { 4, 19 }; return r; }
            case 'O': { byte[] r = { 4, 20 }; return r; }
            case 'P': { byte[] r = { 4, 21 }; return r; }
            case 'Q': { byte[] r = { 4, 22 }; return r; }
            case 'R': { byte[] r = { 4, 23 }; return r; }
            case 'S': { byte[] r = { 4, 24 }; return r; }
            case 'T': { byte[] r = { 4, 25 }; return r; }
            case 'U': { byte[] r = { 4, 26 }; return r; }
            case 'V': { byte[] r = { 4, 27 }; return r; }
            case 'W': { byte[] r = { 4, 28 }; return r; }
            case 'X': { byte[] r = { 4, 29 }; return r; }
            case 'Y': { byte[] r = { 4, 30 }; return r; }
            case 'Z': { byte[] r = { 4, 31 }; return r; }
            // This is incorrect in the case of version 1 files.
            // Fortunately, version 1 files don't really exist.
            case ' ': { byte[] r = { 5, 6 }; return r; }
            case '\n': { byte[] r = { 5, 7 }; return r; }
            case '0': { byte[] r = { 5, 8 }; return r; }
            case '1': { byte[] r = { 5, 9 }; return r; }
            case '2': { byte[] r = { 5, 10 }; return r; }
            case '3': { byte[] r = { 5, 11 }; return r; }
            case '4': { byte[] r = { 5, 12 }; return r; }
            case '5': { byte[] r = { 5, 13 }; return r; }
            case '6': { byte[] r = { 5, 14 }; return r; }
            case '7': { byte[] r = { 5, 15 }; return r; }
            case '8': { byte[] r = { 5, 16 }; return r; }
            case '9': { byte[] r = { 5, 17 }; return r; }
            case '.': { byte[] r = { 5, 18 }; return r; }
            case ',': { byte[] r = { 5, 19 }; return r; }
            case '!': { byte[] r = { 5, 20 }; return r; }
            case '?': { byte[] r = { 5, 21 }; return r; }
            case '_': { byte[] r = { 5, 22 }; return r; }
            case '#': { byte[] r = { 5, 23 }; return r; }
            case '\'': { byte[] r = { 5, 24 }; return r; }
            case '"': { byte[] r = { 5, 25 }; return r; }
            case '/': { byte[] r = { 5, 26 }; return r; }
            case '\\': { byte[] r = { 5, 27 }; return r; }
            case '-': { byte[] r = { 5, 28 }; return r; }
            case ':': { byte[] r = { 5, 29 }; return r; }
            case '(': { byte[] r = { 5, 30 }; return r; }
            case ')': { byte[] r = { 5, 31 }; return r; }
            default: { byte[] r = { 0 }; return r; }
        }
    }
    
    public char getCharAt(int index) {
        short c = this.chars[index];
        
        if(c >= 32 && c <= 126) {
            return (char)c;
        } else if(c >= 155 && c <= 251) {
            return EXTRA_CHARACTERS[c - 155];
        } else if(c == 13 || c == 10) {
            return '\n';
        } else {
            return '?';
        }
    }
}
