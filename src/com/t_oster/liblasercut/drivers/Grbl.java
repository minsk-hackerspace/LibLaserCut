/**
 * This file is part of LibLaserCut.
 * Copyright (C) 2011 - 2014 Thomas Oster <mail@thomas-oster.de>
 *
 * LibLaserCut is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibLaserCut is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with LibLaserCut. If not, see <http://www.gnu.org/licenses/>.
 *
 **/
package com.t_oster.liblasercut.drivers;

import com.t_oster.liblasercut.ProgressListener;
import com.t_oster.liblasercut.platform.Util;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.ArrayDeque;

/**
 * This class implements a driver for Grbl based firmwares.
 *
 * @author Michael Adams <zap@michaeladams.org>
 */
public class Grbl extends GenericGcodeDriver
{
  protected ArrayDeque<Integer> commandLenQueue;

  public Grbl()
  {
    commandLenQueue = new ArrayDeque<Integer>();
    //set some grbl-specific defaults
    setLineend("CR");
    setIdentificationLine("Grbl");
    // Grbl uses "ok" flow control
    setWaitForOKafterEachLine(true);
    setPreJobGcode(getPreJobGcode()+",M3");
    // turn off laser before returning to home position
    setPostJobGcode("M5,"+getPostJobGcode());
    // Grbl & MrBeam use 1000 for 100% PWM on the spindle/laser
    setSpindleMax(1000.0f);
    // Grbl doesn't turn off laser during G0 rapids
    setBlankLaserDuringRapids(true);
    // grbl can take a while to answer if doing a lot of slow moves
    setSerialTimeout(30000);
    
    // blank these so that connect automatically uses serial first
    setHttpUploadUrl(null);
    setHost(null);
  }
  
  protected static final String SETTING_AUTO_HOME = "Automatically home laser cutter";
  
  @Override
  public String[] getPropertyKeys()
  {
    List<String> result = new LinkedList<String>();
    result.addAll(Arrays.asList(super.getPropertyKeys()));
    result.remove(GenericGcodeDriver.SETTING_HOST);
    result.remove(GenericGcodeDriver.SETTING_HTTP_UPLOAD_URL);
    result.remove(GenericGcodeDriver.SETTING_AUTOPLAY);
    result.remove(GenericGcodeDriver.SETTING_IDENTIFICATION_STRING);
    result.remove(GenericGcodeDriver.SETTING_WAIT_FOR_OK);
    result.remove(GenericGcodeDriver.SETTING_TRAVEL_SPEED);
    result.remove(GenericGcodeDriver.SETTING_LINEEND);
    result.add(SETTING_AUTO_HOME);
    return result.toArray(new String[0]);
  }
  
  @Override
  public Object getProperty(String attribute) {
    if (SETTING_AUTO_HOME.equals(attribute)) {
      return this.getAutoHome();
    }
    else {
      return super.getProperty(attribute);
    }
  }
  
  @Override
  public void setProperty(String attribute, Object value) {
    if (SETTING_AUTO_HOME.equals(attribute)) {
      this.setAutoHome((Boolean) value);
    }
    else {
      super.setProperty(attribute, value);
    }
  }
  
  protected boolean autoHome = true;
  
  public boolean getAutoHome()
  {
    return autoHome;
  }
  
  public void setAutoHome(boolean auto_home)
  {
    this.autoHome = auto_home;
  }

  protected boolean simpleStreamMode = true;

  public boolean isSimpleStreamMode()
  {
    return simpleStreamMode;
  }

  public void setSimpleStreamMode(boolean mode) throws IOException
  {
    if (mode != simpleStreamMode) {
      if (!simpleStreamMode)
        waitForCommandsCompletion();
      else
        commandLenQueue.clear();

      simpleStreamMode = mode;
    }
  }

  @Override
  public String getModelName()
  {
    return "Grbl Gcode Driver";
  }

  protected void sendLineWithoutWait(String text, Object... parameters) throws IOException
  {
    boolean wasSetWaitingForOk = isWaitForOKafterEachLine();
    setWaitForOKafterEachLine(false);
    sendLine(text, parameters);
    setWaitForOKafterEachLine(wasSetWaitingForOk);
  }

  @Override
  protected String waitForLine() throws IOException
  {
    String line = "";
    while ("".equals(line))
    {//skip empty lines
      line = in.readLine();
    }

    //TODO: remove
    if (isSimpleStreamMode() || !"ok".equals(line))
      System.out.println("< "+line);
    else {
      int len = commandLenQueue.peek();
      // Debug: print counted chars still remains in the buffer AFTER receiving this 'ok' response
      System.out.println(String.format(FORMAT_LOCALE, "%d< %s", getBufferedCommandsLen() - len, line));
    }

    return line;
  }

  protected static final int GRBL_BUF_LEN = 128;

  protected Integer getBufferedCommandsLen()
  {
    Integer len = 0;
    for (Integer c : commandLenQueue)
      len += c;
    return len;
  }

  protected void sendLineSimple(String text, Object... parameters) throws IOException
  {
    super.sendLine(text, parameters);
  }

  protected void sendLineCC(String text, Object... parameters) throws IOException
  {
    String outStr = String.format(FORMAT_LOCALE, text+LINEEND(), parameters);
    int len = outStr.length();

    // Read all received responses from grbl or wait for needed free space in the serial buffer
    while (in.ready() || (getBufferedCommandsLen() + len > GRBL_BUF_LEN)) {
      String line = waitForLine();
      if (!"ok".equals(line))
      {
        throw new IOException("Lasercutter did not respond 'ok', but '"+line+"'instead.");
      }
      commandLenQueue.remove();
    }

    commandLenQueue.add(len);
    out.print(outStr);
    //TODO: Remove
    System.out.println(String.format(FORMAT_LOCALE, "%d> %s", getBufferedCommandsLen(), outStr));
    out.flush();
  }

  protected void waitForCommandsCompletion() throws IOException
  {
    while (!commandLenQueue.isEmpty()) {
      String line = waitForLine();
      if (!"ok".equals(line))
      {
        throw new IOException("Lasercutter did not respond 'ok', but '"+line+"'instead.");
      }
      commandLenQueue.remove();
    }
  }

  
  /**
   * Initializes Grbl, handling issuing of soft-reset and initial homing
   * (if desired & required).
   * @param pl Progress listener to update during connect/homing process
   * @return
   * @throws IOException
   */
  @Override
  protected String waitForIdentificationLine(ProgressListener pl) throws IOException
  {
    // flush serial buffer
    while (in.ready()) { in.readLine(); }
    commandLenQueue.clear();
    simpleStreamMode = true;

    // send reset character to Grbl to get it to print out its welcome message
    pl.taskChanged(this, "Sending soft reset");
    out.write(0x18);
    
    String error = super.waitForIdentificationLine(pl);
    if (error != null) return error;
    
    // check if board is locked and if so home/unlock
    if (in.ready() && waitForLine().contains("'$H'|'$X' to unlock]")) {
      if (getAutoHome() == true) {
        pl.taskChanged(this, "Homing");
        sendLineWithoutWait("$H");
        
        // wait for "ok" or "[Caution: Unlocked]" followed by "ok"
        String line = waitForLine();
        if (!line.equals("ok"))
          line = waitForLine();
        if (!line.equals("ok"))
          throw new IOException("Homing cycle failed to complete");
      }
      else {
        throw new IOException("Grbl is locked");
      }
    }
    
    return null;
  }
  
  /**
   * Send a G0 rapid move to Grbl.
   * Doesn't include travel speed since grbl ignores that anyway.
   * 
   * @param out
   * @param x
   * @param y
   * @param resolution
   * @throws IOException 
   */
  @Override
  protected void move(PrintStream out, double x, double y, double resolution) throws IOException {
    x = isFlipXaxis() ? getBedWidth() - Util.px2mm(x, resolution) : Util.px2mm(x, resolution);
    y = isFlipYaxis() ? getBedHeight() - Util.px2mm(y, resolution) : Util.px2mm(y, resolution);
    currentSpeed = getTravel_speed();

    String line = "G0";

    if (resolution >= 254) {
      line += String.format(FORMAT_LOCALE, "X%.3fY%.3f",x,y);
    } else {
      line += String.format(FORMAT_LOCALE, "X%.2fY%.2f",x,y);
    }

    currentX = x;
    currentY = y;

    if (blankLaserDuringRapids)
    {
      currentPower = 0.0;
      line += "S0";
    }

    sendLine(line);

  }

  /**
   * Send a line of gcode to the cutter, stripping out any whitespace in the process
   * @param text
   * @param parameters
   * @throws IOException 
   */
  @Override

  protected void sendLine(String text, Object... parameters) throws IOException
  {
    if (isSimpleStreamMode())
      sendLineSimple(text, parameters);
    else
      sendLineCC(text, parameters);
  }

  @Override
  protected void sendJobPrepare() throws IOException {
    setSimpleStreamMode(false);
  }

  @Override
  protected void sendJobFinish() throws IOException {
    setSimpleStreamMode(true);
  }

  @Override
  protected void setKeysMissingFromDeserialization()
  {
    super.setKeysMissingFromDeserialization();
    commandLenQueue = new ArrayDeque<Integer>();
    simpleStreamMode = true;
  }

  @Override
  public Grbl clone()
  {
    Grbl clone = new Grbl();
    clone.copyProperties(this);
    return clone;
  }
}
