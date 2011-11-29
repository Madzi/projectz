/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package zmachine;

import java.io.File;

/**
 *
 * @author katharine
 */
public interface ZIO {
    public static final int LOAD = 0;
    public static final int SAVE = 1;
    public static final int SCORE = 0;
    public static final int TIME = 1;
    
    public void outputString(String str);
    public void outputLine(String line);
    public void outputComment(String comment);
    public String readLine();
    public File chooseFile(String prompt, int type);
    public void setStatus(String place, int a, int b, int type);
    
    public boolean confirm(String question);
    
    public void reset();
    
    public void splitWindow(int lines);
    public void setWindow(int win);
}
