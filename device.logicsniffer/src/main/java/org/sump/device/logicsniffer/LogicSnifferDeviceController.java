/*
 * OpenBench LogicSniffer / SUMP project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110, USA
 *
 * Copyright (C) 2006-2010 Michael Poppitz, www.sump.org
 * Copyright (C) 2010 J.W. Janssen, www.lxtreme.nl
 */
package org.sump.device.logicsniffer;


import java.beans.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import javax.swing.*;

import nl.lxtreme.ols.api.*;
import nl.lxtreme.ols.api.data.*;
import nl.lxtreme.ols.api.devices.*;


/**
 * GUI Component that allows the user to control the device and start captures.
 * <p>
 * Its modelled after JFileChooser and should allow for non-dialog
 * implementations making it somewhat reusable.
 * 
 * @version 0.7
 * @author Michael "Mr. Sump" Poppitz
 */
public class LogicSnifferDeviceController implements DeviceController
{
  // INNER TYPES

  /**
   * Provides a SwingWorker implementation for capturing data in the background.
   */
  static final class CaptureWorker extends SwingWorker<CapturedData, Integer>
  {
    // CONSTANTS

    public static final String PROP_CAPTURE_ABORTED = "captureAborted";
    public static final String PROP_CAPTURE_DONE = "captureDone";
    public static final String PROP_CAPTURE_PROGRESS = "progress";

    // VARIABLES

    private final LogicSnifferDevice device;
    private final String portName;
    private final int baudrate;

    // CONSTRUCTORS

    /**
     * Creates a new CaptureWorker.
     * 
     * @param aDevice
     *          the Logic Sniffer device to use for capturing data, cannot be
     *          <code>null</code>.
     */
    public CaptureWorker( final LogicSnifferDevice aDevice, final String aPortName, final int aBaudrate )
    {
      this.device = aDevice;
      this.portName = aPortName;
      this.baudrate = aBaudrate;
    }

    // METHODS

    /**
     * @see javax.swing.SwingWorker#doInBackground()
     */
    @Override
    protected CapturedData doInBackground() throws Exception
    {
      if ( LOG.isLoggable( Level.INFO ) )
      {
        LOG.info( "Run started" );
      }

      if ( !this.device.attach( this.portName, this.baudrate ) )
      {
        throw new IOException( "Unable to open port " + this.portName + ". No specific reason..." );
      }

      try
      {
        return this.device.run( new ProgressCallback()
        {
          @Override
          public void updateProgress( final int aPercentage )
          {
            setProgress( aPercentage );
          }
        } );
      }
      finally
      {
        this.device.detach();
      }
    }

    /**
     * @see javax.swing.SwingWorker#done()
     */
    @Override
    protected void done()
    {
      CapturedData data = null;
      String abortReason = null;

      try
      {
        data = get();
      }
      catch ( CancellationException exception )
      {
        abortReason = ""; // simply cancelled by user...
      }
      catch ( ExecutionException exception )
      {
        abortReason = exception.getCause().getMessage();
      }
      catch ( InterruptedException exception )
      {
        abortReason = exception.getMessage();
      }

      // Report the result back to the given callback...
      if ( isCancelled() || ( abortReason != null ) )
      {
        firePropertyChange( PROP_CAPTURE_ABORTED, null, abortReason );
      }
      else if ( isDone() )
      {
        firePropertyChange( PROP_CAPTURE_DONE, null, data );
      }
      else
      {
        if ( LOG.isLoggable( Level.WARNING ) )
        {
          LOG.warning( "Internal state error: not done nor cancelled?!" );
        }
      }
    }
  }

  // CONSTANTS

  private static final String NAME = "OpenBench LogicSniffer";

  private static final Logger LOG = Logger.getLogger( LogicSnifferDeviceController.class.getName() );

  // VARIABLES

  private final LogicSnifferDevice device;
  private final LogicSnifferConfigDialog configDialog;
  private CaptureWorker captureWorker;
  private boolean setup;

  // CONSTRUCTORS

  /**
   * Constructs device controller component.
   */
  public LogicSnifferDeviceController()
  {
    this.device = new LogicSnifferDevice();
    this.configDialog = new LogicSnifferConfigDialog( this.device );

    this.setup = false;
  }

  // METHODS

  /**
   * @see nl.lxtreme.ols.api.devices.DeviceController#captureData(nl.lxtreme.ols.api.devices.CaptureCallback)
   */
  @Override
  public void captureData( final CaptureCallback aCallback ) throws IOException
  {
    final String portName = this.configDialog.getPortName();
    final int baudrate = this.configDialog.getPortBaudrate();

    this.captureWorker = new CaptureWorker( this.device, portName, baudrate );

    // Listen to various properties for reporting it to our callback...
    this.captureWorker.addPropertyChangeListener( new PropertyChangeListener()
    {
      /**
       * @see java.beans.PropertyChangeListener#propertyChange(java.beans.PropertyChangeEvent)
       */
      @Override
      public void propertyChange( final PropertyChangeEvent aEvent )
      {
        final Object value = aEvent.getNewValue();
        final String propertyName = aEvent.getPropertyName();

        if ( CaptureWorker.PROP_CAPTURE_PROGRESS.equals( propertyName ) )
        {
          final Integer progress = ( Integer )value;
          if ( LOG.isLoggable( Level.FINE ) )
          {
            LOG.fine( "Progress: " + progress );
          }

          aCallback.updateProgress( progress );
        }
        else if ( CaptureWorker.PROP_CAPTURE_ABORTED.equals( propertyName ) )
        {
          final String abortReason = ( String )value;
          if ( LOG.isLoggable( Level.INFO ) )
          {
            LOG.info( "Run aborted: " + abortReason );
          }

          aCallback.captureAborted( abortReason );
        }
        else if ( CaptureWorker.PROP_CAPTURE_DONE.equals( propertyName ) )
        {
          final CapturedData data = ( CapturedData )value;
          if ( LOG.isLoggable( Level.INFO ) )
          {
            LOG.info( "Run completed: " + ( data == null ? "<no data!>" : "<with data>" ) );
          }

          aCallback.captureComplete( data );
        }
      }
    } );

    // Let the capturing take place in a background thread...
    this.captureWorker.execute();
  }

  /**
   * @see java.awt.Component#getName()
   */
  public String getName()
  {
    return NAME;
  }

  /**
   * @see nl.lxtreme.ols.api.devices.DeviceController#isSetup()
   */
  @Override
  public boolean isSetup()
  {
    return this.setup;
  }

  /**
   * @see nl.lxtreme.ols.api.Configurable#readProperties(String, java.util.Properties)
   */
  @Override
  public void readProperties( final String aNamespace, final Properties aProperties )
  {
    this.configDialog.readProperties( aNamespace, aProperties );
  }

  /**
   * Displays the device controller dialog with enabled configuration portion
   * and waits for user input.
   * 
   * @see nl.lxtreme.ols.api.devices.DeviceController#setupCapture()
   */
  @Override
  public boolean setupCapture()
  {
    this.setup = this.configDialog.showDialog();
    return this.setup;
  }

  /**
   * @see nl.lxtreme.ols.api.Configurable#writeProperties(String, java.util.Properties)
   */
  @Override
  public void writeProperties( final String aNamespace, final Properties aProperties )
  {
    this.configDialog.writeProperties( aNamespace, aProperties );
  }
}
