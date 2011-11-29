/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package projectz;

import zmachine.ZIO;
import zmachine.ZMachine;
import zmachine.ZError;

import java.io.File;

/**
 *
 * @author katharine
 */
public class ZRunner extends Thread {
    private ZIO io;
    private ZMachine z;
    public ZRunner(ZIO io, File file) {
        this.io = io;
        this.z = new ZMachine(this.io, file);
    }
    
    @Override
    public void run() {
        try {
            z.init();
            z.run();
        } catch(ZError e) {
            io.outputComment("\nError: " + e.getMessage());
        }
        this.z = null;
        if(this.io != null)
            io.outputComment("Completed execution.");
    }
    
    public void terminate() {
        if(this.z != null)
            this.z.stop();
        this.z = null;
        this.io = null;
    }
}
