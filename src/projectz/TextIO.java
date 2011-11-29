/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package projectz;

import zmachine.ZIO;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.File;

/**
 *
 * @author katharine
 */
public class TextIO implements ZIO {
    
    BufferedReader in;
    
    public TextIO() {
        this.in = new BufferedReader(new InputStreamReader(System.in));
    }
    
    @Override
    public void outputLine(String line) {
        System.out.println(line);
    }
    
    @Override
    public void outputString(String text) {
        System.out.print(text);
    }
    
    @Override
    public void outputComment(String comment) {
        this.outputLine(comment);
    }
    
    @Override
    public String readLine() {
        try {
            return in.readLine();
        } catch(IOException e) {
            return "";
        }
    }
    
    @Override
    public File chooseFile(String prompt, int type) {
        System.out.print(prompt + ": ");
        return new File(this.readLine());
    }
    
    @Override
    public boolean confirm(String question) {
        System.out.print("\n" + question + " ('yes' or 'no')");
        String response = this.readLine();
        return "yes".equals(response.toLowerCase());
    }
    
    @Override
    public void splitWindow(int lines) {
        // Unimplemented.
    }
    
    @Override
    public void setWindow(int win) {
        // Unimplemented.
    }
    
    @Override
    public void reset() {
        // Unimplemented.
    }
    
    @Override
    public void setStatus(String place, int a, int b, int type) {
        // Unimplemented.
    }
}
