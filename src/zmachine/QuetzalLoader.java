/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package zmachine;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.EOFException;
import java.io.File;
import iff.Chunk;

/**
 *
 * @author katharine
 */
public class QuetzalLoader {
    private ZMachine machine;
    
    public QuetzalLoader(ZMachine z) {
        this.machine = z;
    }
    
    public void load(File file)
            throws FileNotFoundException, IOException, ZError {
        FileInputStream f = new FileInputStream(file);
        
        Chunk form = new Chunk(f);
        if(!form.readString(4).equals("IFZS")) {
            throw new QuetzalError("File is not a quetzal save file.");
        }
        
        while(true) {
            Chunk chunk;
            try {
                chunk = new Chunk(form);
            } catch(EOFException e) {
                break;
            }
            
            String name = chunk.getName();
            if("IFhd".equals(name)) this.headerChunk(chunk);
            else if("CMem".equals(name)) this.compressedMemoryChunk(chunk);
            else if("UMem".equals(name)) this.uncompressedMemoryChunk(chunk);
            else if("Stks".equals(name)) this.stacksChunk(chunk);
            else chunk.skip();
        }
    }
    
    private void headerChunk(Chunk chunk) throws IOException, ZError {
        int release = chunk.readUnsignedShort();
        short[] serial = chunk.read(6);
        int checksum = chunk.readUnsignedShort();
        short[] pcBytes = chunk.read(3);
        int pc = (pcBytes[0] << 16) | (pcBytes[1] << 8) | pcBytes[2];
        if(this.machine.unsignedNumber(0x02) != release || 
                checksum != this.machine.unsignedNumber(0x1C)) {
            throw new QuetzalError("Wrong game");
        }
        
        this.machine.init();
        this.machine.pc = pc - 1; // We disagree on where pc should be counted.
        chunk.close();
    }
    
    private void compressedMemoryChunk(Chunk chunk) throws IOException, ZError {
        short[] cmem = chunk.read();
        int pointer = 0;
        boolean skipping = false;
        for(short b : cmem) {
            if(b != 0 && !skipping) {
                this.machine.memory[pointer++] ^= b;
            } else {
                skipping = !skipping;
                if(skipping) {
                    ++pointer;
                } else {
                    pointer += b;
                }
            }
        }
        
        if(skipping) {
            throw new QuetzalError("Bad save data");
        }
        if(pointer > this.machine.memoryDynamicEnd) {
            throw new QuetzalError("Save data overruns dynamic memory area.");
        }
        
        chunk.close();
    }
    
    private void uncompressedMemoryChunk(Chunk chunk) throws IOException, ZError {
        short[] umem = chunk.read();
        if(umem.length != this.machine.memoryDynamicEnd) {
            throw new QuetzalError("Uncompressed memory image is the wrong size.");
        }
        System.arraycopy(umem, 0, this.machine.memory, 0, umem.length);
        
        chunk.close();
    }
    
    private void stacksChunk(Chunk chunk) throws IOException, ZError {
        while(true) {
            short[] pcArray;
            try {
                pcArray = chunk.read(3);
            } catch(EOFException e) {
                break;
            }
            int pc = (pcArray[0] << 16) | (pcArray[1] << 8) | pcArray[2];
            short flags = chunk.readUnsignedByte();
            boolean discardResult = (flags & 0x10) == 0x10;
            int localCount = flags & 0x0F;
            short returnVariable = chunk.readUnsignedByte();
            short argsSupplied = chunk.readUnsignedByte();
            int stackSize = chunk.readUnsignedShort();
            int[] locals = chunk.readUnsignedShorts(localCount);
            int[] stack = chunk.readUnsignedShorts(stackSize);
            
            if(pc > 0) {
                this.machine.callStack[this.machine.callStackPointer++] = (argsSupplied << 8) | localCount;
                this.machine.callStack[this.machine.callStackPointer++] = returnVariable;
                this.machine.callStack[this.machine.callStackPointer++] = pc - 1;
                this.machine.callStack[this.machine.callStackPointer++] = this.machine.stackPointer;
            }
            for(int var : locals) {
                this.machine.stack[this.machine.stackPointer++] = var;
            }
            for(int word : stack) {
                this.machine.stack[this.machine.stackPointer++] = word;
            }
        }
    }
}
