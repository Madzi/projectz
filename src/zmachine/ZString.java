/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package zmachine;

/**
 *
 * @author katharine
 */
public class ZString {
    // ZCharacters are five bits, and are stored in groups of three in
    // 15 bits spread across two bytes, like so:
    //   --first byte-------   --second byte---
    //   7    6 5 4 3 2  1 0   7 6 5  4 3 2 1 0
    //   bit  --first--  --second---  --third--
    // The "bit" is used to indicate end-of-string.
    public static ZString fromMemory(ZMachine z, int address) {
        // First we work out how big our array has to be.
        int i = address;
        int length = 0;
        while(true) {
            length += 3;
            if((z.memory[i] & 0x80) == 0x80) {
                break;
            }
            i += 2;
        }
        byte[] chars = new byte[length];
        
        // Now we actually build our string.
        i = 0;
        for(int j = address; (i < length); j += 2) {
            chars[i++] = (byte)((z.memory[j] >> 2) & 0x1F);
            chars[i++] = (byte)(((z.memory[j] & 0x03) << 3) | ((z.memory[j+1] >> 5) & 0x1F));
            chars[i++] = (byte)(z.memory[j+1] & 0x1F);
        }
        
        // Now we have our characters.
        return new ZString(z, chars);
    }
    
    protected byte[] chars;
    protected ZMachine z;
    
    // For Z-Char parsing
    protected char[] a0, a1, a2;
    
    public ZString(ZMachine z, byte[] chars) {
        this.z = z;
        this.chars = chars;
        
        // Initialise the alphabets a0, a1 and a2.
        // a2 varies between versions 1 and 2/3.
        char[] t0 = { 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l',
            'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'};
        char[] t1 = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L',
            'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z'};
        this.a0 = t0;
        this.a1 = t1;
        if(this.z.version == 1) {
            char[] t2 = { ' ', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                '.', ',', '!', '?', '_', '#', '\'', '"', '/', '\\', '<', '-',
                ':', '(', ')' };
            this.a2 = t2;
        } else {
            char[] t2 = { ' ', '\n', '0', '1', '2', '3', '4', '5', '6', '7', 
                '8', '9', '.', ',', '!', '?', '_', '#', '\'', '"', '/', '\\',
                '-', ':', '(', ')' };
            this.a2 = t2;
        }
    }
    
    public ZSCIIString toZSCII() throws ZError {
        return this.toZSCII(true);
    }
    
    public ZSCIIString toZSCII(boolean allow_abbreviation_expansion) throws ZError {
        // length * 5 is arbitrary, and might not be enough. If it's not
        // the interpter will crash, which is probably suboptimal.
        short[] zscii = new short[this.chars.length * 5];
        int alphabet = 0;
        int last_alphabet = 0;
        boolean temporary = false;
        int i = 0, j = 0; // i for this.chars, j for zscii.
        // Helpful tip:
        // - i is always preincrement (++i)
        // - j is always postincrement (j++)
        while(i < this.chars.length) {
            // Bail if we're out of characters.
            // (this should never happen.)
            if(i >= this.chars.length) {
                break;
            }
            byte zchar = this.chars[i];
            // In versions 1 and 2, zchar 2 moves to the next alphabet.
            // In all versions, zchar 4 does the same.
            // Additionally, zchar 2 in all versions, and zchar 3 in
            // version 3 is only a temporary shift (4 is permanent in 1 and 2)
            if((this.z.version < 3 && zchar == 2) || zchar == 4) {
                last_alphabet = alphabet;
                alphabet = (alphabet + 1) % 3;
                temporary = (zchar == 2 || this.z.version >= 3);
            // Same logic for zchars 3/5 as 2/4, except moving two alphabets.
            } else if((this.z.version < 3 && zchar == 3) || zchar == 5) {
                last_alphabet = alphabet;
                alphabet = (alphabet + 2) % 3;
                temporary = (zchar == 3 || this.z.version >= 3);
            } else {
                if(zchar == 0) {
                    zscii[j++] = 32; // ZSCII space (32)
                } else if(zchar == 1 && this.z.version == 1) {
                    zscii[j++] = 13; // ZSCII newline (12)
                }
                // In versions >= 2, there exist abbreviations containing further
                // ZStrings that can be referenced. In v2 only char 1 works for
                // this purpose; in v3 chars 1, 2 and 3 all perform this job.
                // Expansion cannot be recursive; inside expansions, expansion
                // characters are silently ignored (allow_abbreviation_expansion)
                // (Perhaps they should be loudly ignored.)
                else if(zchar <= 3 && this.z.version >= 2 && allow_abbreviation_expansion) {
                    if(zchar == 1 || this.z.version >= 3) {
                        int offset = this.chars[++i];
                        ZString zabbr = ZString.fromMemory(this.z, 2 * this.z.unsignedNumber(this.z.abbreviationStart + 2 * ((32 * (zchar - 1)) + offset)));
                        short[] abbr = zabbr.toZSCII(false).toBytes();
                        System.arraycopy(abbr, 0, zscii, j, abbr.length);
                        j += abbr.length;
                    }
                }
                // Character 6 from alphabet 2 is a ZSCII escape sequence:
                // the next two Z-characters are the high and low (respectively)
                // parts of a 10-bit ZSCII sequence that should be combined.
                else if(zchar == 6 && alphabet == 2) {
                    if(i + 2 >= this.chars.length) {
                        break;
                    }
                    short high = (short)(this.chars[++i] << 5);
                    short low = this.chars[++i];
                    zscii[j++] = (short)(high | low);
                }
                // ZChars >= six are simply pulled from the relevant alphabet
                // table.
                else if(zchar >= 6) {
                    int index = zchar - 6;
                    short result;
                    switch(alphabet) {
                        case 0:
                            result = (short)this.a0[index];
                            break;
                        case 1:
                            result = (short)this.a1[index];
                            break;
                        case 2:
                            result = (short)this.a2[index];
                            break;
                        default:
                            // This can never happen, but not checking it makes
                            // java sad.
                            throw new ZError("Invalid alphabet selected.");
                    }
                    zscii[j++] = result;
                }
                
                // If we had a temporary shift (see above), reverse it.
                // (this line is not encountered if we did on this char)
                if(temporary) {
                    alphabet = last_alphabet;
                }
            }
            ++i; // Next character!
        }
        
        // Now we need to trim our array to the right length.
        // j contains a pointer to the cell after our last character, and is
        // therefore equal to the string length.
        short[] output = new short[j];
        System.arraycopy(zscii, 0, output, 0, j);
        
        // Finished!
        return new ZSCIIString(this.z, output);
    }
    
    short[] toBytes(int count) {
        // Combine our Z-chars into bytes.
        short[] words = new short[count];
        int i = 0, j = 0;
        while(i < this.chars.length) {
            int word = (this.chars[i++] << 10) | (this.chars[i++] << 5) | (this.chars[i++]);
            words[j++] = (short)((word >> 8) & 0xFF);
            words[j++] = (short)(word & 0xFF);
        }
        words[count-2] |= 0x80; // End-of-string flag.
        return words;
    }
    
    short length() {
        return (short)this.chars.length;
    }
    
    short getChar(int i) {
        return this.chars[i];
    }
}
