package zmachine;

import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.File;
import java.util.logging.Logger;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Random;
import org.apache.commons.collections.primitives.ArrayShortList;

/**
 *
 * @author katharine
 */
public class ZMachine {
    public static final int OPCODE_FORMAT_SHORT = 1;
    public static final int OPCODE_FORMAT_LONG = 2;
    public static final int OPCODE_FORMAT_VARIABLE = 3;
    
    public static final byte OPERAND_TYPE_LARGE = 0;
    public static final byte OPERAND_TYPE_SMALL = 1;
    public static final byte OPERAND_TYPE_VAR = 2;
    public static final byte OPERAND_TYPE_OMITTED = 3;
    
    public static final int STORY_MAX_SIZE = 131072; // 128 kilobytes
    public static final int STACK_SIZE = 1000;
    public static final int CALL_STACK_SIZE = 1000;
    
    protected File filename;
    protected int version;
    
    // Memory
    protected short[] memory;
    protected int memorySize; // Contains actual memory image size
    protected int memoryDynamicEnd;
    protected int memoryStaticStart;
    protected int memoryStaticEnd;
    protected int memoryHighStart;
    protected int memoryHighEnd;
    
    // Tables
    protected int dictionaryStart;
    protected int objectTableStart;
    protected int globalVariableStart;
    protected int abbreviationStart;
    
    // Lexical parsing
    protected short[] wordSeparators;
    protected int dictionaryEntryLength;
    protected int dictionaryLength;
    
    // Execution
    protected int pc = 0;
    protected int[] stack; // Because Java doesn't like primitives in Stacks.
    protected short stackPointer;
    protected int[] callStack;
    protected short callStackPointer;
    protected boolean running;
    
    // Randomness
    protected Random rng;
    
    // Stats
    protected int opcodesExecuted;
    
    // I/O
    protected ZIO io;
    
    public ZMachine(ZIO io, File filename) {
        this.io = io;
        this.filename = filename;
        this.memory = new short[STORY_MAX_SIZE];
    }
    
    public boolean init() throws ZError {
        short[] story = this.loadStory();
        if(story == null) {
            return false;
        }
        this.memory = story;
        this.initVM();
        return true;
    }
    
    public void run() throws ZError {
        this.running = true;
        this.mainLoop();
    }
    
    public void stop() {
        this.running = false;
    }
    
    protected void mainLoop() throws ZError {
        while(this.running) {
            this.executeCycle();
        }
        this.io.outputComment("VM terminated. Executed " + this.opcodesExecuted + " opcodes.");
    }
    
    protected short[] loadStory() {
        try {
            FileInputStream s = new FileInputStream(this.filename);
            DataInputStream d = new DataInputStream(s);
            short[] story = new short[STORY_MAX_SIZE];
            int i = 0;
            while(true) {
                try {
                    story[i++] = (short)d.readUnsignedByte();
                } catch(EOFException e) {
                    break;
                }
            }
            this.memorySize = i;
            s.close();
            return story;
        }
        catch(IOException e) {
            return null;
        }
    }
    
    private void initVM() throws ZError {
        this.version = this.memory[0x00];
        // We only support file versions 1, 2 and 3.
        if(this.version > 3) {
            throw new StoryError("Invalid version number.");
        }
        
        // Set up memory bounds
        this.memoryHighEnd = this.memorySize - 1;
        this.memoryDynamicEnd = this.unsignedNumber(0x0E);
        this.memoryStaticStart = this.memoryDynamicEnd + 1;
        this.memoryStaticEnd = this.memorySize - 1;
        // Maximum allowable static memory is 64 kB
        if(this.memoryStaticEnd < 0xFFFF) {
            this.memoryStaticEnd = 0xFFFF;
        }
        this.memoryHighStart = this.unsignedNumber(0x04);
        this.dictionaryStart = this.unsignedNumber(0x08);
        this.objectTableStart = this.unsignedNumber(0x0A);
        this.globalVariableStart = this.unsignedNumber(0x0C);
        this.abbreviationStart = this.unsignedNumber(0x18);
        
        // Prepare the operational structures
        this.pc = this.unsignedNumber(0x06); // Initial PC
        this.stack = new int[STACK_SIZE];
        this.stackPointer = 0;
        this.callStack = new int[CALL_STACK_SIZE];
        this.callStackPointer = 0;
        
        // Reset stats
        this.opcodesExecuted = 0;
        
        // Initialise the RNG.
        this.rng = new Random();
        
        // Prepare the dictionary
        this.initDictionary();
        
        // We're done!
        
    }
    
    protected void initDictionary() throws ZError {
        short separator_count = this.memory[this.dictionaryStart];
        this.wordSeparators = new short[separator_count];
        System.arraycopy(this.memory, this.dictionaryStart + 1, this.wordSeparators, 0, separator_count);
        Arrays.sort(this.wordSeparators); // Must be sorted because we use Arrays.binarySearch later on.
        this.dictionaryEntryLength = this.memory[this.dictionaryStart + separator_count + 1];
        this.dictionaryLength = this.unsignedNumber(this.dictionaryStart + separator_count + 2);
    }
    
    protected int unsignedNumber(int address) throws ZError {
        if(address >= this.memoryHighEnd) {
            throw new StoryError("Illegal attempt to retrieve data from past the end of high memory");
        }
        return (this.memory[address] << 8) | (this.memory[address + 1]);
    }
    
    protected int signedNumber(int address) throws ZError {
        return this.sign(this.unsignedNumber(address));
    }
    
    protected int sign(int unsigned) {
        return this.sign(unsigned, 16);
    }
    
    protected int sign(int unsigned, int bits) {
        if((unsigned & 1 << (bits - 1)) != 0) {
            return (unsigned - (1 << bits));
        }
        return unsigned;
    }
    
    protected void setNumber(int address, int number) {
        int high = (number >>> 8) & 0xFF;
        int low = number & 0xFF;
        this.memory[address] = (short)high;
        this.memory[address+1] = (short)low;
    }
    
    protected int unpackAddress(int address) {
        return address * 2;
    }
    
    protected void executeCycle() throws ZError {
        int opcode = this.memory[this.pc];
        int toIncrement = 1;
        int format;
        int operandCount = -1;
        boolean reallyVariable = false;
        
        byte[] operandTypes = null;
        
        // There are several parts to this:
        // First we determine an actual operation code, and the number
        // of operands it takes.
        
        // Having done that, the next step is to actually pull the
        // operands out of memory.
        if((opcode & 0xC0) == 0xC0) {
            format = OPCODE_FORMAT_VARIABLE;
            if((opcode & 0x20) == 0) {
                operandCount = 2;
            } else {
                reallyVariable = true;
            }
            opcode = opcode & 0x1F;
        } else if((opcode & 0x80) == 0x80) {
            format = OPCODE_FORMAT_SHORT;
            if((opcode & 0x30) == 0x30) {
                operandCount = 0;
            } else {
                operandTypes = new byte[1];
                operandCount = 1;
                if((opcode & 0x30) == 0) {
                    operandTypes[0] = OPERAND_TYPE_LARGE;
                } else if((opcode & 0x10) == 0x10) {
                    operandTypes[0] = OPERAND_TYPE_SMALL;
                } else if((opcode & 0x20) == 0x20) {
                    operandTypes[0] = OPERAND_TYPE_VAR;
                } else {
                    throw new StoryError(String.format("Nonsense in ExecuteCycle! PC = %d, opcode = %d, OPERAND_FORMAT_SHORT",
                            this.pc, opcode));
                }
            }
            opcode = opcode & 0x0F;
        } else {
            format = OPCODE_FORMAT_LONG;
            operandCount = 2;
            operandTypes = new byte[2];
            if((opcode & 0x40) == 0x40) {
                operandTypes[0] = OPERAND_TYPE_VAR;
            } else {
                operandTypes[0] = OPERAND_TYPE_SMALL;
            }
            if((opcode & 0x20) == 0x20) {
                operandTypes[1] = OPERAND_TYPE_VAR;
            } else {
                operandTypes[1] = OPERAND_TYPE_SMALL;
            }
            opcode = opcode & 0x1F;
            toIncrement++;
        }
        
        if(format == OPCODE_FORMAT_VARIABLE) {
            toIncrement++;
            this.pc++;
            short bits = this.memory[this.pc];
            operandTypes = new byte[4];
            operandCount = 0;
            for(int i = 0; i < 4; ++i) {
                byte type = (byte)((bits >>> (3 - i) * 2) & 0x03);
                if(type != OPERAND_TYPE_OMITTED) {
                    operandCount++;
                }
                operandTypes[i] = type;
            }
        }
        
        // Now that we know what our operands are, we pull them out.
        // For large types, that's an unsigned short, otherwise it's an
        // unsigned byte. If we have OPERAND_TYPE_VAR, the unsigned byte
        // represents a variable, so resolve that too.
        int[] operands = new int[operandCount];
        for(int i = 0; i < operandCount; ++i) {
            if(operandTypes[i] == OPERAND_TYPE_LARGE) {
                operands[i] = this.unsignedNumber(this.pc + 1);
                this.pc += 2;
            } else if(operandTypes[i] == OPERAND_TYPE_SMALL) {
                operands[i] = this.memory[++this.pc];
            } else if(operandTypes[i] == OPERAND_TYPE_VAR) {
                operands[i] = this.getVariable(this.memory[++this.pc]);
            }
        }
        
        // Actually call the function!
        this.callOp(opcode, operandCount, reallyVariable, operands);
        
        // Onward!
        this.pc++;
    }
    
    // Utility functions.
    protected int getVariable(int variable) throws ZError {
        return getVariable(variable, false);
    }
    
    protected int getVariable(int variable, boolean signed) throws ZError {
        int value = 0;
        // Variable 0x00 pops the stack.
        if(variable == 0x00) {
            return this.stack[--this.stackPointer];
        }
        // Variables above 0x10 are global variables, and are stored in the
        // global variable area (two bytes each starting at global_variable_start)
        else if(variable >= 0x10) {
            if(variable > 0xFF) {
                throw new StoryError(String.format("Attempted to read illegal global variable %d", variable));
            }
            int address = this.globalVariableStart + ((variable - 0x10) * 2);
            if(signed) {
                value = this.signedNumber(address);
            } else {
                value = this.unsignedNumber(address);
            }
        }
        // Below 0x10 we're returning local variables. If the call stack has
        // non-zero size we're returning arguments; otherwise we're just returning
        // those at the root of the stack (does this make sense?).
        else if(this.callStackPointer > 0) {
            value = this.stack[this.callStack[this.callStackPointer-1] + variable - 1];
        } else {
            value = this.stack[variable - 1];
        }
        value = value & 0xFFFF;
        return value;
    }
    
    protected void setVariable(int variable, int value) throws ZError {
        if(variable == 0x00) {
            this.stack[this.stackPointer++] = value;
        } else if(variable >= 0x10) {
            if(variable > 0xFF) {
                throw new StoryError(String.format("Attempted to write illegal global variable %d", variable));
            }
            int address = this.globalVariableStart + ((variable - 0x10) * 2);
            this.setNumber(address, value);
        } else if(this.callStackPointer > 0) {
            this.stack[this.callStack[this.callStackPointer-1] + variable - 1] = value;
        } else {
            this.stack[variable - 1] = value;
        }
    }
    
    // Finds the address of the given object ID.
    // Objects are stored as nine-byte entries in the object table.
    // The initial 62 bytes store the default properties.
    protected int getObjectAddress(int obj) throws ZError {
        if(obj < 0 || obj > 255) {
            throw new StoryError(String.format("Attempted to find illegal object %d", obj));
        }
        if(obj == 0) {
            return 0;
        }
        return (this.objectTableStart + 62 + ((obj - 1) * 9));
    }
    
    protected boolean getObjectAttribute(int obj, int attribute) throws ZError {
        if(attribute > 31) {
            throw new StoryError("Attempted to get invalid attribute " + attribute);
        }
        if(obj == 0) {
            return false;
            //throw new StoryError("Attempted to read attribute from null object.");
        }
        int address = this.getObjectAddress(obj);
        // The attributes are 32 bits stored in the first four bytes.
        // "bits" is the bit in the byte we're looking for.
        // "part" is the byte we have to look in.
        int bits = 0x80 >>> (attribute % 8);
        int part = attribute / 8;
        return (this.memory[address + part] & bits) == bits;
    }
    
    protected void setObjectAttribute(int obj, int attribute, boolean value) throws ZError {
        if(attribute > 31) {
            throw new StoryError("Attempted to set invalid attribute " + attribute);
        }
        if(obj == 0) {
            throw new StoryError("Attmpted to set attribute on null object.");
        }
        // See GetObjectAttribute for an explanation.
        int address = this.getObjectAddress(obj);
        int bits = 0x80 >>> (attribute % 8);
        int part = attribute / 8;
        if(!value) {
            this.memory[address + part] &= ~bits; // (AND NOT bits) to unset
        } else {
            this.memory[address + part] |= bits; // (OR bits) to set.
        }
    }
    
    protected int getObjectPropertyTableAddress(int obj) throws ZError {
        if(obj == 0) {
            //throw new StoryError("Attempted to read property table for null object");
            return 0;
        }
        // The property table address is the last two bytes of the object (n+7)
        return this.unsignedNumber(this.getObjectAddress(obj) + 7);
    }
    
    protected ZString getObjectName(int obj) throws ZError {
        if(obj == 0) {
            throw new StoryError("Attempted to get name of null object");
        }
        return ZString.fromMemory(this, this.getObjectPropertyTableAddress(obj) + 1);
    }
    
    protected int getObjectPropertyAddress(int obj, int prop) throws ZError {
        int address = this.getObjectPropertyTableAddress(obj);
        // The first byte contains the length of the name (in words), which
        // immediately followed that byte (§12.4). Skip the name.
        address += this.memory[address] * 2 + 1;
        // Properties are stored in descending numerical order, terminated by
        // a property with ID 0 and size -1 (i.e. size byte 0).
        while(this.memory[address] != 0) {
            // Property number and size are stored together in a single byte;
            // size_byte = (32 * size) - 1 + prop_num
            // where 0 < prop_num ≤ 32
            int propNum = this.memory[address] % 32;
            int size = this.memory[address] / 32 + 1;
            // If this is the property we want, return its address.
            if(propNum == prop) {
                return address + 1;
            // If the property number is smaller than the property we want, we
            // have passed our intended target and should abort.
            } else if(propNum < prop) {
                return 0;
            }
            // Skip to the next property.
            address += size + 1;
        }
        // Nothing found; return null.
        return 0;
    }
    
    protected int getDefaultPropertyAddress(int prop) throws ZError {
        return this.objectTableStart + (prop - 1) * 2;
    }
    
    protected int getPropertySize(int obj, int prop) throws ZError {
        int address = this.getObjectPropertyAddress(obj, prop) - 1;
        return this.memory[address] / 32 + 1;
    }
    
    protected int getObjectParent(int obj) throws ZError {
        if(obj == 0) {
            return 0; // The null object has a null parent.
                      // If it doesn't HHGG crashes.
        }
        int address = this.getObjectAddress(obj);
        return this.memory[address + 4];
    }
    
    protected int getObjectSibling(int obj) throws ZError {
        if(obj == 0) {
            throw new StoryError("Attmpted to find sibling of null object");
        }
        int address = this.getObjectAddress(obj);
        return this.memory[address + 5];
    }
    
    protected int getObjectChild(int obj) throws ZError {
        if(obj == 0) {
            //throw new StoryError("Attmpted to find child of null object");
            return 0;
        }
        int address = this.getObjectAddress(obj);
        return this.memory[address + 6];
    }
    
    protected int getObjectPreviousSibling(int obj) throws ZError {
        // Objects do not contain back-references, so to do this
        // we must go to the object's parent's child (which, by definition,
        // is the first object at that level), then traverse the siblings of
        // these objects until we find the object whose sibling is the object
        // we were originally trying to find the previous sibling of.
        // If no such object is found the tree is not well founded, which is
        // illegal.
        // The one exception is that if the child is the initial child of its
        // parent object, there is no previous sibling (so we return null)
        int objectParent = this.getObjectParent(obj);
        if(objectParent == 0) {
            // There exists one object with no parent, and it has no siblings.
            // TODO: Is it an error to make this request?
            return 0;
        }
        int parentChild = this.getObjectChild(objectParent);
        if(parentChild == obj) {
            return 0; // We are the first child; no prior sibling.
        } else {
            int thisObject = parentChild;
            while(true) {
                int nextObject = this.getObjectSibling(thisObject);
                if(nextObject == obj) {
                    return thisObject;
                } else {
                    thisObject = nextObject;
                    if(thisObject == 0) {
                        throw new StoryError("The tree is not well-founded.");
                    }
                }
            }
        }
    }
    
    protected void removeObject(int obj) throws ZError {
        int address = this.getObjectAddress(obj);
        int previousSibling = this.getObjectPreviousSibling(obj);
        if(previousSibling == 0) {
            int parent = this.memory[address + 4];
            if(parent > 0) {
                int parentAddress = this.getObjectAddress(parent);
                // parent's child = next sibling
                this.memory[parentAddress + 6] = this.memory[address + 5];
            }
        } else {
            int previousAddress = this.getObjectAddress(previousSibling);
            // previous sibling's sibling = my sibling
            this.memory[previousAddress + 5] = this.memory[address + 5];
        }
        this.memory[address + 5] = 0; // My sibling = null
        this.memory[address + 4] = 0; // My parent = null
    }
    
    // Inserts an object obj as the first child of an object destination.
    protected void insertObject(int obj, int destination) throws ZError {
        int objAddr = this.getObjectAddress(obj);
        int destAddr = this.getObjectAddress(destination);
        
        int previousSibling = this.getObjectPreviousSibling(obj);
        //System.out.println("previous_sibling: " + previous_sibling);
        if(previousSibling == 0) {
            // Set the child of the parent of the object to the sibling of the object
            this.memory[this.getObjectAddress(this.memory[objAddr + 4]) + 6] = this.memory[objAddr + 5];
            //System.out.println((this.GetObjectAddress(this.memory[obj_addr + 4]) + 6) + " =a " + this.memory[obj_addr + 5]);
        } else {
            // Set the object that this object was a sibling of's sibling to the sibling of this object.
            this.memory[this.getObjectAddress(previousSibling) + 5] = this.memory[objAddr + 5];
            //System.out.println((this.GetObjectAddress(previous_sibling) + 5) + " =b " + this.memory[obj_addr + 5]);
        }
        
        // Set the sibling of the object to the child of the destination
        this.memory[objAddr + 5] = this.memory[destAddr + 6];
        //System.out.println((obj_addr + 5) + " = " + this.memory[dest_addr + 6]);
        // Set the child of the destination to the object
        this.memory[destAddr + 6] = (short)obj;
        //System.out.println((dest_addr + 6) + " = " + (short)obj);
        // Set the parent of the object to the destination
        this.memory[objAddr + 4] = (short)destination;
        //System.out.println((obj_addr + 4) + " = " + (short)destination);
    }
    
    protected boolean loadSave(File file) {
        QuetzalLoader loader = new QuetzalLoader(this);
        try {
            loader.load(file);
        } catch(IOException e) {
            this.io.outputComment("Error: Save file not found.");
            return false;
        } catch(ZError e) {
            this.io.outputComment("Error loading save file: " + e.getMessage());
            return false;
        }
        return true;
    }
    
    protected boolean saveGame(File file) {
        QuetzalSaver saver = new QuetzalSaver(this);
        try {
            saver.writeSave(file);
        } catch(IOException e) {
            this.io.outputComment("Couldn't write to file: " + e.getMessage());
            return false;
        } catch(ZError e) {
            this.io.outputComment("Internal error saving: " + e.getMessage());
            return false;
        }
        return true;
    }
    
    protected void updateStatus() throws ZError {
        String location;
        try {
            location = this.getObjectName(this.getVariable(0x10)).toZSCII().toString();
        } catch(StoryError e) {
            location = "Nowhere";
        }
        int a = this.getVariable(0x11, true);
        int b = this.getVariable(0x12, true);
        int type = ZIO.SCORE;
        if(this.version == 3 && (this.memory[0x01] & 0x80) == 0x80) {
            type = ZIO.TIME;
        }
        this.io.setStatus(location, a, b, type);
    }
    
    // Effectively the "return" value of opcodes, where applicable.
    protected void store(int value) throws ZError {
        short variable = this.memory[++this.pc];
        this.setVariable(variable, value);
    }
    
    // Generally used by conditional instructions (e.g. je, etc.)
    protected void branch(boolean result) throws ZError {
        short branch = this.memory[++this.pc];
        
        // The required result is stored in the top bit of the branch byte.
        // The target is stored in the bottom six bits. If the second bit is
        // set the target also includes the following byte, in big-endian order.
        // The target is then a 14-bit signed number (to allow jumping backwards)
        boolean requiredResult = (branch & 0x80) != 0;
        int target = branch & 0x3F;
        if((branch & 0x40) == 0) {
            target = target << 8;
            target |= this.memory[++this.pc];
        }
        target = this.sign(target, 14);
        //System.out.println("Branch target: " + target);
        
        // We only do anything interesting (besides the above PC adjustment) if
        // the result is the required result.
        if(result == requiredResult) {
            if(target == 0) {
                this.returnFromRoutine(0); // target = 0 means "return false"
            } else if(target == 1) {
                this.returnFromRoutine(1); // target = 1 means "return true"
            } else {
                this.pc += target - 2; // Offset by 2 to allow for 0 and 1 being special.
            }
        }
    }
    
    protected int locateStringInDictionary(ZString zstring) {
        int index = this.dictionaryLength / 2;
        int lowerBound = 0;
        int upperBound = this.dictionaryLength;
        int k = this.dictionaryEntryLength;
        int start = this.dictionaryStart + this.memory[this.dictionaryStart] + 4;
        short[] bytes = zstring.toBytes(4);
        while(true) {
            int direction = 0;
            for(int j = 0; j < 4; ++j) {
                short chr = this.memory[start  +index*k + j];
                if(chr == bytes[j]) {
                    continue;
                } else if(chr > bytes[j]) {
                    direction = -1;
                    break;
                } else {
                    direction = 1;
                    break;
                }
            }
            if(direction == 0) {
                return start + index * k;
            } else if(upperBound == lowerBound) {
                return 0;
            } else if(direction < 0) {
                upperBound = index;
            } else if(direction > 0) {
                lowerBound = index + 1;
            }
            index = (lowerBound + upperBound) / 2;
        }
    }
    
    protected void tokeniseZSCII(int tableAddress, ZSCIIString zscii) throws ZError {
        ArrayList<ArrayShortList> words = new ArrayList<ArrayShortList>();
        ArrayShortList wordStarts = new ArrayShortList();
        ArrayShortList nextWord = new ArrayShortList();
        short lastNewWord = 0;
        short[] zsciiBytes = zscii.toBytes();
        for(short i = 0; i < zsciiBytes.length; ++i) {
            short chr = zsciiBytes[i];
            if(chr == 32 || Arrays.binarySearch(this.wordSeparators, chr) >= 0) {
                if(nextWord.size() > 0) {
                    words.add(nextWord);
                    wordStarts.add(lastNewWord);
                    nextWord = new ArrayShortList();
                }
                // If the separator is not a space it counts as a word, so we add it.
                if(chr != 32) {
                    ArrayShortList chrlist = new ArrayShortList(1);
                    chrlist.add(chr);
                    words.add(chrlist);
                    wordStarts.add(i);
                }
                lastNewWord = (short)(i + 1);
            } else {
                nextWord.add(chr);
            }
        }
        if(nextWord.size() > 0) {
            words.add(nextWord);
            wordStarts.add(lastNewWord);
        }
        
        // Store the parsed data.
        // First byte is the number of words.
        this.memory[tableAddress + 1] = (short)words.size();
        for(short i = 0; i < words.size(); ++i) {
            // If i > table size, abort so we don't overrun.
            if(i >= this.memory[tableAddress]) {
                break;
            }
            ArrayShortList word = words.get(i);
            ZString zstring = (new ZSCIIString(this, word.toArray()).toZString(4));
            int pos = this.locateStringInDictionary(zstring);
            this.setNumber(tableAddress + i*4 + 2 + 0, pos);
            this.memory[tableAddress + i*4 + 2 + 2] = (short)word.size();
            this.memory[tableAddress + i*4 + 2 + 3] = (short)(wordStarts.get(i) + 1);
        }
    }
    
    // Used to return from procedures. Split out from the 'ret' operand because
    // it's used by other functions here, too.
    protected void returnFromRoutine(int value) throws ZError {
        int stackTop = this.callStack[--this.callStackPointer];
        this.pc = this.callStack[--this.callStackPointer];
        int var = this.callStack[--this.callStackPointer];
        --this.callStackPointer; // Pop off useless word.
        this.stackPointer = (short)stackTop;
        this.setVariable(var, value);
    }
    
    // Deals with calling the appropriate functions.
    protected void callOp(int op, int argCount, boolean variable, int ... args) throws ZError {
        //System.out.println(String.format("Calling op_%s_%x%s @0x%06X", (variable ? "var" : argCount + "op"), op, Arrays.toString(args), this.pc));
        if(variable) {
            switch(op) {
                case 0: this.op_call(args); break;
                case 1: this.op_storew(args[0], args[1], args[2]); break;
                case 2: this.op_storeb(args[0], args[1], args[2]); break;
                case 3: this.op_put_prop(args[0], args[1], args[2]); break;
                case 4: this.op_read(args[0], args[1]); break;
                case 5: this.op_print_char(args[0]); break;
                case 6: this.op_print_num(args[0]); break;
                case 7: this.op_random(args[0]); break;
                case 8: this.op_push(args[0]); break;
                case 9: this.op_pull(args[0]); break;
                case 10: this.op_split_window(args[0]); break;
                case 11: this.op_set_window(args[0]); break;
            }
        } else if(argCount == 0) {
            switch(op) {
                case 0: this.op_rtrue(); break;
                case 1: this.op_rfalse(); break;
                case 2: this.op_print(); break;
                case 3: this.op_print_ret(); break;
                case 4: this.op_nop(); break;
                case 5: this.op_save(); break;
                case 6: this.op_restore(); break;
                case 7: this.op_restart(); break;
                case 8: this.op_ret_popped(); break;
                case 9: this.op_pop(); break;
                case 10: this.op_quit(); break;
                case 11: this.op_new_line(); break;
                case 12: this.op_show_status(); break;
                case 13: this.op_verify(); break;
            }
        } else if(argCount == 1) {
            switch(op) {
                case 0: this.op_jz(args[0]); break;
                case 1: this.op_get_sibling(args[0]); break;
                case 2: this.op_get_child(args[0]); break;
                case 3: this.op_get_parent(args[0]); break;
                case 4: this.op_get_prop_len(args[0]); break;
                case 5: this.op_inc(args[0]); break;
                case 6: this.op_dec(args[0]); break;
                case 7: this.op_print_addr(args[0]); break;
                // case 8 doesn't exist.
                case 9: this.op_remove_obj(args[0]); break;
                case 10: this.op_print_obj(args[0]); break;
                case 11: this.op_ret(args[0]); break;
                case 12: this.op_jump(args[0]); break;
                case 13: this.op_print_paddr(args[0]); break;
                case 14: this.op_load(args[0]); break;
                case 15: this.op_not(args[0]); break;
            }
        } else if(argCount == 2) {
            switch(op) {
                case 1: this.op_je(args[0], args[1]); break;
                case 2: this.op_jl(args[0], args[1]); break;
                case 3: this.op_jg(args[0], args[1]); break;
                case 4: this.op_dec_chk(args[0], args[1]); break;
                case 5: this.op_inc_chk(args[0], args[1]); break;
                case 6: this.op_jin(args[0], args[1]); break;
                case 7: this.op_test(args[0], args[1]); break;
                case 8: this.op_or(args[0], args[1]); break;
                case 9: this.op_and(args[0], args[1]); break;
                case 10: this.op_test_attr(args[0], args[1]); break;
                case 11: this.op_set_attr(args[0], args[1]); break;
                case 12: this.op_clear_attr(args[0], args[1]); break;
                case 13: this.op_store(args[0], args[1]); break;
                case 14: this.op_insert_obj(args[0], args[1]); break;
                case 15: this.op_loadw(args[0], args[1]); break;
                case 16: this.op_loadb(args[0], args[1]); break;
                case 17: this.op_get_prop(args[0], args[1]); break;
                case 18: this.op_get_prop_addr(args[0], args[1]); break;
                case 19: this.op_get_next_prop(args[0], args[1]); break;
                case 20: this.op_add(args[0], args[1]); break;
                case 21: this.op_sub(args[0], args[1]); break;
                case 22: this.op_mul(args[0], args[1]); break;
                case 23: this.op_div(args[0], args[1]); break;
            }
        } else if(argCount == 3 && op == 1) {
            this.op_je(args[0], args[1], args[2]);
        } else if(argCount == 4 && op == 1) {
            this.op_je(args[0], args[1], args[2], args[3]);
        } else {
            throw new StoryError("Unknown opcode!");
        }
        ++this.opcodesExecuted;
    }
    
    // Opcodes!
    
    protected void op_call(int ... args) throws ZError {
        int routine = this.unpackAddress(args[0]);
        // Routine 0 always returns false immediately.
        if(routine == 0) {
            this.store(0);
            return;
        }
        // Routines are stored in memory with a byte containing the number
        // of variables they have, followed by a default value for each of
        // those variables (two bytes each), followed by the actual code.
        short varcount = this.memory[routine];
        if(varcount > 15) {
            throw new StoryError(String.format("Calling address %d without a routine!", routine));
        }
        this.pc++;
        // This value isn't used in execution, but is required to store the save
        // files.
        this.callStack[this.callStackPointer++] = (((0x7F >>> args.length - 1)) << 8) | varcount;
        // These are needed to return from the routine we're calling.
        this.callStack[this.callStackPointer++] = this.memory[this.pc];
        this.callStack[this.callStackPointer++] = this.pc;
        this.callStack[this.callStackPointer++] = this.stackPointer;
        // If we have an argument, push that onto the stack.
        // If we don't, push the default onto the stack.
        for(int i = 0; i < varcount; ++i) {
            if(i + 1 < args.length) {
                this.stack[this.stackPointer++] = args[i+1];
            } else {
                this.stack[this.stackPointer++] = this.unsignedNumber(routine + i*2 + 1);
            }
        }
        
        // Jump into the routine!
        this.pc = routine + varcount * 2;
    }
    
    protected void op_storew(int arr, int wordIndex, int value) {
        this.setNumber(arr + 2 * wordIndex, value);
    }
    
    protected void op_storeb(int arr, int byteIndex, int value) {
        this.memory[arr + byteIndex] = (short)value;
    }
    
    protected void op_put_prop(int obj, int prop, int value) throws ZError {
        int address = this.getObjectPropertyAddress(obj, prop);
        int size = this.getPropertySize(obj, prop);
        if(size == 1) {
            this.memory[address] = (short)value;
        } else if(size == 2) {
            this.setNumber(address, value);
        } else {
            throw new StoryError("Illegal put_prop on property of size > 2");
        }
    }
    
    protected void op_read(int textAddress, int parseTable) throws ZError {
        this.updateStatus();
        String input = this.io.readLine();
        if(input == null) {
            return;
        }
        int max_length = this.memory[textAddress];
        ZSCIIString zscii = new ZSCIIString(this, input.toLowerCase().substring(0, max_length <= input.length() ? max_length : input.length()));
        short[] bytes = zscii.toBytes();
        System.arraycopy(bytes, 0, this.memory, textAddress + 1, bytes.length);
        this.memory[textAddress + 1 + bytes.length] = 0;
        this.tokeniseZSCII(parseTable, zscii);
    }
    
    protected void op_print_char(int chr) {
        short[] achr = { (short)chr };
        this.io.outputString((new ZSCIIString(this, achr)).toString());
    }
    
    protected void op_print_num(int num) {
        this.io.outputString(String.valueOf(num));
    }
    
    protected void op_random(int r) throws ZError {
        // r = 0 re-seeds the generator.
        r = this.sign(r);
        if(r == 0) {
            this.rng = new Random(); // This seems to be the only way to re-seed.
        } else if(r < 0) {
            this.rng.setSeed(r * -1);
        } else {
            this.store(this.rng.nextInt(r) + 1);
        }
    }
    
    protected void op_push(int value) {
        this.stack[this.stackPointer++] = value;
    }
    
    protected void op_pull(int variable) throws ZError {
        this.setVariable(variable, this.stack[--this.stackPointer]);
    }
    
    protected void op_split_window(int lines) {
        this.io.splitWindow(lines);
    }
    
    protected void op_set_window(int win) {
        this.io.setWindow(win);
    }
    
    protected void op_set_output_stream(int number) {
        //
    }
    
    protected void op_set_input_stream(int number) {
        //
    }
    
    protected void op_rtrue() throws ZError {
        this.returnFromRoutine(1);
    }
    
    protected void op_rfalse() throws ZError {
        this.returnFromRoutine(0);
    }
    
    protected void op_print() throws ZError {
        ZString zchars = ZString.fromMemory(this, this.pc + 1);
        this.pc += zchars.length() / 3 * 2;
        this.io.outputString(zchars.toZSCII().toString());
    }
    
    protected void op_print_ret() throws ZError {
        this.op_print();
        this.op_new_line();
        this.returnFromRoutine(1);
    }
    
    protected void op_nop() {
        // This does nothing. no-op!
    }
    
    protected void op_save() throws ZError {
        File file = this.io.chooseFile("Choose a Save File", ZIO.SAVE);
        if(file == null) {
            this.io.outputComment("No file selected.");
            this.branch(false);
        } else {
            this.branch(this.saveGame(file));
        }
    }
    
    protected void op_restore() throws ZError {
        File file = this.io.chooseFile("Choose a Save File", ZIO.LOAD);
        if(file == null) {
            this.io.outputComment("No file selected.");
            this.branch(false);
        } else {
            this.branch(this.loadSave(file));
        }
    }
    
    protected void op_restart() throws ZError {
        this.init();
        this.io.reset();
        this.pc--;
    }
    
    protected void op_ret_popped() throws ZError {
        this.returnFromRoutine(this.stack[--this.stackPointer]);
    }
    
    protected void op_pop() {
        --this.stackPointer;
    }
    
    protected void op_quit() {
        this.memory = null;
        this.stack = null;
        this.callStack = null;
        this.running = false;
    }
    
    protected void op_new_line() {
        this.io.outputLine("");
    }
    
    protected void op_show_status() throws ZError {
        this.updateStatus();
    }
    
    protected void op_verify() throws ZError {
        // This should perform checksum verification on the game file,
        // but I'm lazy, so we'll assume it's always valid.
        this.branch(true);
    }
    
    protected void op_jz(int a) throws ZError {
        this.branch(a == 0);
    }
    
    protected void op_get_sibling(int obj) throws ZError {
        int sibling = this.getObjectSibling(obj);
        //System.out.println("sibling: " + sibling);
        this.store(sibling);
        this.branch(sibling != 0);
    }
    
    protected void op_get_child(int obj) throws ZError {
        short child = (short)this.getObjectChild(obj);
        this.store(child);
        this.branch(child != 0);
    }
    
    protected void op_get_parent(int obj) throws ZError {
        this.store(this.getObjectParent(obj));
    }
    
    protected void op_get_prop_len(int address) throws ZError {
        if(address == 0) {
            this.store(0);
        } else {
            this.store(this.memory[address - 1] / 32 + 1);
        }
    }
    
    protected void op_inc(int variable) throws ZError {
        this.setVariable(variable, this.getVariable(variable) + 1);
    }
    
    protected void op_dec(int variable) throws ZError {
        this.setVariable(variable, this.getVariable(variable) - 1);
    }
    
    protected void op_print_addr(int address) throws ZError {
        ZString zchars = ZString.fromMemory(this, address);
        this.io.outputString(zchars.toZSCII().toString());
    }
    
    protected void op_remove_obj(int obj) throws ZError {
        this.removeObject(obj);
    }
    
    protected void op_print_obj(int obj) throws ZError {
        this.io.outputString(this.getObjectName(obj).toZSCII().toString());
    }
    
    protected void op_ret(int value) throws ZError {
        this.returnFromRoutine(value);
    }
    
    protected void op_jump(int label) {
        int offset = this.sign(label);
        this.pc += offset - 2;
    }
    
    protected void op_print_paddr(int paddr) throws ZError {
        this.op_print_addr(this.unpackAddress(paddr));
    }
    
    protected void op_load(int variable) throws ZError {
        this.store(this.getVariable(variable));
    }
    
    protected void op_not(int value) throws ZError {
        this.store((~value) & 0xFFFF);
    }
    
    protected void op_je(int ... args) throws ZError {
        int value = args[0];
        for(int i = 1; i < args.length; ++i) {
            if(args[i] == value) {
                this.branch(true);
                return;
            }
        }
        this.branch(false);
    }
    
    protected void op_jl(int a, int b) throws ZError {
        this.branch(this.sign(a) < this.sign(b));
    }
    
    protected void op_jg(int a, int b) throws ZError {
        this.branch(this.sign(a) > this.sign(b));
    }
    
    protected void op_dec_chk(int variable, int value) throws ZError {
        short varValue = (short)this.getVariable(variable);
        varValue--;
        varValue &= 0xFFFF;
        this.setVariable(variable, varValue);
        this.branch(this.sign(varValue) < this.sign(value));
    }
    
    protected void op_inc_chk(int variable, int value) throws ZError {
        short varValue = (short)this.getVariable(variable);
        varValue++;
        varValue &= 0xFFFF;
        this.setVariable(variable, varValue);
        this.branch(this.sign(varValue) > this.sign(value));
    }
    
    protected void op_jin(int obj1, int obj2) throws ZError {
        this.branch(this.getObjectParent(obj1) == obj2);
    }
    
    protected void op_test(int bitmap, int flags) throws ZError {
        this.branch((bitmap & flags) == flags);
    }
    
    protected void op_or(int a, int b) throws ZError {
        this.store(a | b);
    }
    
    protected void op_and(int a, int b) throws ZError {
        this.store(a & b);
    }
    
    protected void op_test_attr(int obj, int attr) throws ZError {
        this.branch(this.getObjectAttribute(obj, attr));
    }
    
    protected void op_set_attr(int obj, int attr) throws ZError {
        this.setObjectAttribute(obj, attr, true);
    }
    
    protected void op_clear_attr(int obj, int attr) throws ZError {
        this.setObjectAttribute(obj, attr, false);
    }
    
    protected void op_store(int variable, int value) throws ZError {
        this.setVariable(variable, value);
    }
    
    protected void op_insert_obj(int obj, int destination) throws ZError {
        this.insertObject(obj, destination);
    }
    
    protected void op_loadw(int array, int word_index) throws ZError {
        this.store(this.unsignedNumber(array + word_index * 2));
    }
    
    protected void op_loadb(int array, int byte_index) throws ZError {
        this.store(this.memory[array + byte_index]);
    }
    
    protected void op_get_prop(int obj, int prop) throws ZError {
        int address = this.getObjectPropertyAddress(obj, prop);
        int size = 2;
        if(address == 0) {
            address = this.getDefaultPropertyAddress(prop);
        } else {
            size = (this.memory[address - 1] / 32) + 1;
        }
        
        if(size == 1) {
            this.store(this.memory[address]);
        } else if(size == 2) {
            this.store(this.unsignedNumber(address));
        } else {
            throw new StoryError("Attmpted get_prop on property of size > 2");
        }
    }
    
    protected void op_get_prop_addr(int obj, int prop) throws ZError {
        this.store(this.getObjectPropertyAddress(obj, prop));
    }
    
    protected void op_get_next_prop(int obj, int prop) throws ZError {
        int address;
        if(prop == 0) {
            address = this.getObjectPropertyTableAddress(obj);
            address += this.memory[address] * 2 + 1;
        } else {
            address = this.getObjectPropertyAddress(obj, prop);
            address += this.memory[address - 1] / 32 + 1;
        }
        
        short next_size_byte = this.memory[address];
        if(address == 0) {
            throw new StoryError("Illegal get_next_prop on nonexistent object property");
        }
        if(next_size_byte == 0) {
            this.store(0);
        } else {
            this.store(next_size_byte % 32);
        }
    }
    
    protected void op_add(int a, int b) throws ZError {
        this.store(this.sign(a) + this.sign(b));
    }
    
    protected void op_sub(int a, int b) throws ZError {
        this.store(this.sign(a) - this.sign(b));
    }
    
    protected void op_mul(int a, int b) throws ZError {
        this.store(this.sign(a) * this.sign(b));
    }
    
    protected void op_div(int a, int b) throws ZError {
        if(b == 0) {
            throw new StoryError("Division by zero.");
        }
        this.store(this.sign(a) / this.sign(b));
    }
}
