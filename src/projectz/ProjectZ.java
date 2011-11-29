/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package projectz;

import zmachine.ZError;
import zmachine.ZIO;
import zmachine.ZMachine;
import java.io.File;

/**
 *
 * @author katharine
 */
public class ProjectZ {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws ZError {
        ZIO io = new TextIO();
        ZMachine z = new ZMachine(io, new File(args[0]));
        z.init();
        z.run();
        io.outputLine("Completed execution.");
    }
}
