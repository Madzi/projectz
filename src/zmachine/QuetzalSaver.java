package zmachine;

import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.EOFException;
import java.io.File;
import org.apache.commons.collections.primitives.ArrayUnsignedByteList;
import org.apache.commons.collections.primitives.ArrayUnsignedShortList;
import iff.OutputChunk;

public class QuetzalSaver {
    private ZMachine machine;
    private short[] originalImage;
    
    public static final boolean COMPRESS_SAVE_FILES = true;
    
    public QuetzalSaver(ZMachine z) {
        this.machine = z;
        short[] initialState = z.loadStory();
        this.originalImage = new short[this.machine.memoryDynamicEnd];
        System.arraycopy(initialState, 0, this.originalImage, 0, this.machine.memoryDynamicEnd);
        initialState = null;
    }
    
    public void writeSave(File file) throws IOException, ZError {
        FileOutputStream f = new FileOutputStream(file);
        
        OutputChunk form = new OutputChunk();
        form.append("IFZS");
        form.append(this.generateIFhd());
        if(COMPRESS_SAVE_FILES) {
            form.append(this.generateCMem());
        } else {
            form.append(this.generateUMem());
        }
        form.append(this.generateStks());
        form.append(this.generateANNO());
        form.append(this.generateAUTH());
        
        f.write(form.getBytes());
    }
    
    private OutputChunk generateIFhd() throws ZError {
        OutputChunk IFhd = new OutputChunk("IFhd");
        IFhd.append(this.machine.unsignedNumber(0x02));
        short[] release = new short[6];
        System.arraycopy(this.machine.memory, 0x12, release, 0, 6);
        IFhd.append(release);
        IFhd.append(this.machine.unsignedNumber(0x1C));
        int pc = this.machine.pc + 1;
        IFhd.append(this.pcToArray(pc));
        return IFhd;
    }
    
    private OutputChunk generateCMem() {
        ArrayUnsignedByteList cmem = new ArrayUnsignedByteList();
        boolean running = false;
        short run = 0;
        for(int i = 0; i < this.machine.memoryDynamicEnd; ++i) {
            short xor = (short)(this.originalImage[i] ^ this.machine.memory[i]);
            if(xor != 0) {
                if(running) {
                    running = false;
                    cmem.add(run);
                }
                cmem.add(xor);
            } else {
                if(running) {
                    ++run;
                    if(run > 255) {
                        cmem.add((short)255);
                        cmem.add((short)0);
                        run = 0;
                    }
                } else {
                    cmem.add((short)0);
                    running = true;
                    run = 0;
                }
            }
        }
        
        if(running) {
            cmem.add(run);
        }
        
        OutputChunk CMem = new OutputChunk("CMem");
        CMem.append(cmem.toArray());
        return CMem;
    }
    
    private OutputChunk generateUMem() {
        short[] umem = new short[this.machine.memoryDynamicEnd];
        System.arraycopy(this.machine.memory, 0, umem, 0, this.machine.memoryDynamicEnd);
        OutputChunk UMem = new OutputChunk("UMem");
        UMem.append(umem);
        return UMem;
    }
    
    private OutputChunk generateStks() {
        OutputChunk Stks = new OutputChunk("Stks");
        int callStackPointer = 0;
        int stackPointer = 0;
        
        // Initial dummy frame - this allows for execution not starting
        // inside a method, and thus the stack being empty.
        // This is written out considerably more verbosely than necessary
        // in order to a) make it clear what's going on, and b) avoid
        // accidental type-related confusion.
        short[] dummyFrameReturnPC = { 0, 0, 0 };
        short dummyFrameFlags = 0;
        short dummyFrameReturnVariable = 0;
        short dummyFrameArgumentMask = 0;
        int dummyFrameStackSize = 0;
        if(this.machine.callStackPointer > 3) 
            dummyFrameStackSize = this.machine.callStack[3];
        int[] dummyFrameLocalVariables = {};
        int[] dummyFrameStack = new int[dummyFrameStackSize];
        for(int i = 0; i < dummyFrameStackSize; ++i)
            dummyFrameStack[i] = this.machine.stack[i];
        
        Stks.append(dummyFrameReturnPC);
        Stks.append(dummyFrameFlags);
        Stks.append(dummyFrameReturnVariable);
        Stks.append(dummyFrameArgumentMask);
        Stks.append(dummyFrameStackSize);
        Stks.append(dummyFrameLocalVariables);
        Stks.append(dummyFrameStack);
        
        while(callStackPointer < this.machine.callStackPointer) {
            short argumentMask = (short)(this.machine.callStack[callStackPointer] >> 8);
            short localCount = (short)(this.machine.callStack[callStackPointer] & 0x0F);
            short returnVariable = (short)this.machine.callStack[++callStackPointer];
            int pc = this.machine.callStack[++callStackPointer] + 1;
            int stackTop = this.machine.callStack[++callStackPointer];
            int frameStackSize;
            if(callStackPointer + 4 >= this.machine.callStackPointer) {
                frameStackSize = this.machine.stackPointer - stackTop;
            } else {
                frameStackSize = this.machine.callStack[callStackPointer + 4] - stackTop;
            }
            frameStackSize -= localCount;
            
            int[] frameLocals = new int[localCount];
            System.arraycopy(this.machine.stack, stackTop, frameLocals, 0, localCount);
            int[] frameStack = new int[frameStackSize];
            System.arraycopy(this.machine.stack, stackTop + localCount, frameStack, 0, frameStackSize);
            
            Stks.append(this.pcToArray(pc));
            Stks.append(localCount);
            Stks.append(returnVariable);
            Stks.append(argumentMask);
            Stks.append(frameStackSize);
            Stks.append(frameLocals);
            Stks.append(frameStack);
            
            callStackPointer++;
        }
        
        return Stks;
    }
    
    private OutputChunk generateANNO() {
        OutputChunk ANNO = new OutputChunk("ANNO");
        ANNO.append("Saved by ZProject");
        return ANNO;
    }
    
    private OutputChunk generateAUTH() {
        OutputChunk AUTH = new OutputChunk("AUTH");
        AUTH.append(System.getProperty("user.name"));
        return AUTH;
    }
    
    private short[] pcToArray(int pc) {
        short[] pcArray = {(short)((pc >> 16) & 0xFF), (short)((pc >> 8) & 0xFF), (short)(pc & 0xFF)};
        return pcArray;
    }
}
