package me.drton.flightplot.export;

import me.drton.flightplot.PreferencesUtil;
import me.drton.jmavlib.conversion.RotationConversion;
import me.drton.jmavlib.log.FormatErrorException;
import me.drton.jmavlib.log.LogReader;
import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.ImageWriteException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.common.RationalNumber;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants;
import org.apache.commons.imaging.formats.tiff.fieldtypes.FieldTypeAscii;
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfo;
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfoByte;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.jfree.data.Range;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.vecmath.Matrix3d;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

public class CamExportDialog extends JDialog {
    private static final String DIALOG_SETTING = "TrackExportDialog";
    private static final String EXPORTER_CONFIGURATION_SETTING = "CamExporterConfiguration";
    private static final String READER_CONFIGURATION_SETTING = "ReaderConfiguration";
    private static final String LAST_EXPORT_DIRECTORY_SETTING = "CamLastExportDirectory";

    private JPanel contentPane;
    private JButton buttonExport;
    private JLabel logEndTimeValue;
    private JTextField timeEndField;
    private JTextField timeStartField;
    private JLabel timeStartLabel;
    private JLabel timeEndLabel;
    private JButton buttonClose;
    private JCheckBox exportDataInChartCheckBox;
    private JLabel statusLabel;
    private JTextField imageNameFormat;
    private JProgressBar exportProgress;
    private JButton buttonCancel;
    private JTextField imageStartNumber;
    private File lastExportDirectory;

    private CamExporterConfiguration exporterConfiguration = new CamExporterConfiguration();
    private TrackReaderConfiguration readerConfiguration = new TrackReaderConfiguration();
    private LogReader logReader;
    private Range chartRange;
    private boolean stopExport;

    private class ExportFormatItem {
        String description;
        String formatName;

        ExportFormatItem(String description, String formatName) {
            this.description = description;
            this.formatName = formatName;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    public CamExportDialog() {
        setContentPane(contentPane);
        setModal(true);
        getRootPane().setDefaultButton(buttonExport);
        setTitle("Image Tag Export Settings");
        buttonExport.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                export();
            }
        });
        buttonClose.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onClose();
            }
        });
        buttonCancel.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopExport = true;
            }
        });
        // call onClose() when cross is clicked
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                onClose();
            }
        });
        // call onClose() on ESCAPE
        contentPane.registerKeyboardAction(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onClose();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        exportDataInChartCheckBox.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent itemEvent) {
                validateTimeRange(null);
            }
        });
        logEndTimeValue.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent mouseEvent) {
                if (!exportDataInChartCheckBox.isSelected()) {
                    timeEndField.setText(stringFromMicroseconds(logReader.getSizeMicroseconds()));
                }
            }
        });

        DocumentListener timeChangedListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                validateTimeRange(null);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validateTimeRange(null);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validateTimeRange(null);
            }
        };
        timeStartField.getDocument().addDocumentListener(timeChangedListener);
        timeEndField.getDocument().addDocumentListener(timeChangedListener);
        pack();
    }

    private String stringFromMicroseconds(long us) {
        return String.format(Locale.ROOT, "%.3f", us / 1000000.0);
    }

    private String formatTime(long time) {
        long s = time / 1000000;
        long ms = (time / 1000) % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
                (int) (s / 3600), s / 60 % 60, s % 60, ms);
    }

    public void display(LogReader logReader, Range chartRange) {
        if (logReader == null) {
            throw new RuntimeException("Log not opened");
        }
        this.logReader = logReader;
        this.chartRange = chartRange;
        readerConfiguration.setTimeStart(logReader.getStartMicroseconds());
        readerConfiguration.setTimeEnd(logReader.getStartMicroseconds() + logReader.getSizeMicroseconds());
        updateDialogFromConfiguration();
        setVisible(true);
    }

    private double getLogSizeInSeconds() {
        return logReader.getSizeMicroseconds() / 1000000.0;
    }

    private File getDestinationFile(String extension, String description) {
        JFileChooser fc = new JFileChooser();
        if (lastExportDirectory != null) {
            fc.setCurrentDirectory(lastExportDirectory);
        }
        FileNameExtensionFilter extensionFilter = new FileNameExtensionFilter(description, extension);
        fc.setFileFilter(extensionFilter);
        fc.setDialogTitle("Export Tags");
        int returnVal = fc.showDialog(null, "Export");
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            lastExportDirectory = fc.getCurrentDirectory();
            String exportFileName = fc.getSelectedFile().toString();
            String exportFileExtension = extensionFilter.getExtensions()[0];
            if (extensionFilter == fc.getFileFilter() && !exportFileName.toLowerCase().endsWith(exportFileExtension)) {
                exportFileName += ("." + exportFileExtension);
            }
            File exportFile = new File(exportFileName);
            if (!exportFile.exists()) {
                return exportFile;
            } else {
                int result = JOptionPane.showConfirmDialog(null,
                        "Do you want to overwrite the existing file?" + "\n" + exportFile.getAbsoluteFile(),
                        "File already exists", JOptionPane.YES_NO_OPTION);
                if (JOptionPane.YES_OPTION == result) {
                    return exportFile;
                }
            }
        }
        return null;
    }

    private void export() {
        updateConfiguration();

        final File file = getDestinationFile("csv", "CVS");

        setStatus("Counting tags...", false);
        stopExport = false;
        buttonCancel.setEnabled(true);
        exportProgress.setValue(0);

        new Thread() {
            public void run() {
                try {
                    List<TrackPoint> tags = new ArrayList<TrackPoint>();

                    int imageNumberStart = 1;
                    try {
                        imageNumberStart = Integer.valueOf(imageStartNumber.getText());
                    } catch (NumberFormatException e) {
                    }

                    long imageStartTime = 0;
                    long tagStartTime = 0;

                    if (null != file) {
                        int missing = 0;
                        int imagesMissing = 0;
                        int last_seq = -1;
                        double currentAverage = 0;
                        long prevDiff = 0;
                        double variance = 0;

                        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                        BufferedWriter logWriter = new BufferedWriter((new FileWriter(new File(file.getAbsolutePath() + ".log"))));

                        writer.write("imagename,latitude,longitude,altitude,pitch,roll,yaw");
                        writer.newLine();

                        logReader.seek(readerConfiguration.getTimeStart());
                        Map<String, Object> data = new HashMap<String, Object>();
                        while (true) {
                            data.clear();
                            long t;
                            try {
                                t = logReader.readUpdate(data);
                            } catch (EOFException e) {
                                break;  // End of file
                            }

                            if (t > readerConfiguration.getTimeEnd()) {
                                break;
                            }

                            Number seq = (Number) data.get("CAMT.seq");

                            if (null != seq) {
                                Number lat = (Number) data.get("CAMT.lat");
                                Number lon = (Number) data.get("CAMT.lon");
                                Number alt = (Number) data.get("CAMT.alt");

                                Number qw = (Number) data.get("CAMT.qw");
                                Number qx = (Number) data.get("CAMT.qy");
                                Number qy = (Number) data.get("CAMT.qx");
                                Number qz = (Number) data.get("CAMT.qz");

                                // Rotate with pitch if set
                                double[] q = {qw.doubleValue(), qx.doubleValue(), qy.doubleValue(), qz.doubleValue()};
                                Matrix3d rot_q = new Matrix3d();
                                //Matrix3d rot_target = new Matrix3d();
                                rot_q.set((RotationConversion.rotationMatrixByQuaternion(q)));
                                //rot_target.set(RotationConversion.rotationMatrixByEulerAngles(0, Math.toRadians(param_pitch_rotation), 0));
                                //rot_q.mul(rot_target);

                                double[] euler = RotationConversion.eulerAnglesByRotationMatrix(rot_q);

                                TrackPoint tag = new TrackPoint(lat.doubleValue(), lon.doubleValue(), alt.doubleValue(), t);
                                tag.sequence = seq.intValue();
                                tag.radRoll = euler[0];
                                tag.radPitch = euler[1];
                                tag.heading = euler[2];

                                tags.add(tag);

                                if (stopExport) {
                                    writer.close();
                                    throw new InterruptedException();
                                }
                            }
                        }

                        setStatus(String.format("Exporting %d tags...", tags.size()), false);
                        exportProgress.setMaximum(tags.size());

                        for (int tagIndex = 0; tagIndex < tags.size(); tagIndex++) {
                            TrackPoint tag = tags.get(tagIndex);

                            if (tagStartTime == 0) {
                                tagStartTime = tag.time;
                            }

                            if (last_seq >= 0 && last_seq + 1 != tag.sequence) {
                                for (int i = last_seq + 1; i < tag.sequence; i++) {
                                    missing++;

                                    String imageName = String.format(imageNameFormat.getText(), i - tags.get(0).sequence + imageNumberStart);
                                    writer.write(imageName);
                                    writer.write(",,,,,,,tag missing");
                                    writer.newLine();
                                }
                            }

                            last_seq = tag.sequence;

                            // We have to subtract the initial sequence number for the image name since the sequence can start anywhere
                            String imageName = String.format(imageNameFormat.getText(), tag.sequence - tags.get(0).sequence + imageNumberStart);

                            writer.write(imageName);
                            writer.write(",");
                            writer.write(String.format(Locale.ENGLISH,"%.7f,%.7f,%.3f,%.3f,%.3f,%.3f",
                                    tag.lat, tag.lon, tag.alt, Math.toDegrees(tag.radPitch), Math.toDegrees(tag.radRoll), Math.toDegrees(tag.heading)));

                            try {
                                File sourceFile = new File(file.getParentFile().getAbsolutePath() + File.separator + imageName);

                                if (sourceFile.exists()) {
                                    boolean missingTs = true;

                                    try {
                                        TiffOutputSet outputSet = null;

                                        // note that metadata might be null if no metadata is found.
                                        final ImageMetadata metadata = Imaging.getMetadata(sourceFile);
                                        final JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
                                        if (null != jpegMetadata) {
                                            // note that exif might be null if no Exif metadata is found.
                                            final TiffImageMetadata exif = jpegMetadata.getExif();

                                            if (null != exif) {
                                                String[] fv = exif.getFieldValue(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);

                                                if (fv != null && fv.length > 0) {
                                                    SimpleDateFormat format1 = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
                                                    Date d1 = format1.parse(fv[0]);

                                                    long imageTime = d1.getTime() * 1000;

                                                    if (imageStartTime == 0) {
                                                        imageStartTime = imageTime;
                                                    }

                                                    int n = tagIndex + 1;
                                                    long diff = (imageTime - imageStartTime) - (tag.time - tagStartTime);

                                                    if (prevDiff == 0) {
                                                        prevDiff = diff;
                                                    }

                                                    long diffDiff = prevDiff - diff;
                                                    currentAverage = (currentAverage * n + diffDiff) / n;
                                                    variance = (variance * n + Math.pow(diffDiff - currentAverage, 2)) / n;
                                                    prevDiff = diff;

                                                    logWriter.write(String.format("tag %d (%.2fs), image %s (%.2fs), time diff: %.2fs",
                                                            tag.sequence, (double)(tag.time - tagStartTime) / 1e6, imageName, (double)(imageTime - imageStartTime) / 1e6,
                                                            (double) diff / 1e6));

                                                    if (Math.abs((double) diff / 1e6) > 10.0) {
                                                        logWriter.write(", LARGE DIFF");
                                                    }

                                                    missingTs = false;
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }

                                    if (missingTs) {
                                        logWriter.write(String.format("tag %d, image %s, missing original date time", tag.sequence, imageName));
                                    }

                                    updateImageMetaData(sourceFile,
                                            new File(file.getParentFile().getAbsolutePath() + File.separator + imageName + "_tagged.jpg"),
                                            tag);

                                } else {
                                    writer.write(",image missing");
                                    logWriter.write("Missing image " + imageName);
                                    imagesMissing++;
                                }

                            } catch (Exception e) {
                                e.printStackTrace();
                                imagesMissing++;
                                writer.write(",image error");
                            }

                            writer.newLine();
                            logWriter.newLine();

                            exportProgress.setValue(tagIndex + 1);

                            if (stopExport) {
                                writer.close();
                                logWriter.close();
                                throw new InterruptedException();
                            }
                        }

                        writer.close();
                        logWriter.close();

                        setStatus(String.format("Exported %d tags, missing tags %d, images not updated %d, sdt dev %.2f", tags.size(), missing, imagesMissing, Math.sqrt(variance) / 1e6), false);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (FormatErrorException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    setStatus("Exported canceled", false);
                }

                buttonCancel.setEnabled(false);
            }
        }.start();
    }

    private void updateImageMetaData(File jpegImageFile, File dst, TrackPoint tag) throws IOException, ImageReadException, ImageWriteException {
        TiffOutputSet outputSet = null;

        // note that metadata might be null if no metadata is found.
        final ImageMetadata metadata = Imaging.getMetadata(jpegImageFile);
        final JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
        if (null != jpegMetadata) {
            // note that exif might be null if no Exif metadata is found.
            final TiffImageMetadata exif = jpegMetadata.getExif();

            if (null != exif) {
                // TiffImageMetadata class is immutable (read-only).
                // TiffOutputSet class represents the Exif data to write.
                //
                // Usually, we want to update existing Exif metadata by
                // changing
                // the values of a few fields, or adding a field.
                // In these cases, it is easiest to use getOutputSet() to
                // start with a "copy" of the fields read from the image.
                outputSet = exif.getOutputSet();
            }
        }

        // if file does not contain any exif metadata, we create an empty
        // set of exif metadata. Otherwise, we keep all of the other
        // existing tags.
        if (null == outputSet) {
            outputSet = new TiffOutputSet();
        }

        outputSet.setGPSInDegrees(tag.lon, tag.lat);
        outputSet.getGPSDirectory().removeField(GpsTagConstants.GPS_TAG_GPS_ALTITUDE);
        outputSet.getGPSDirectory().add(GpsTagConstants.GPS_TAG_GPS_ALTITUDE, RationalNumber.valueOf(tag.alt));
        outputSet.getGPSDirectory().removeField(GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF);
        outputSet.getGPSDirectory().add(GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF,
                (byte)GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF_VALUE_ABOVE_SEA_LEVEL);

        final TiffOutputDirectory exifDirectory = outputSet.getOrCreateRootDirectory();
        exifDirectory.removeField(ExifTagConstants.EXIF_TAG_SOFTWARE);
        exifDirectory.add(ExifTagConstants.EXIF_TAG_SOFTWARE, "FlightPlot");


        OutputStream os = new FileOutputStream(dst);
        os = new BufferedOutputStream(os);

        new ExifRewriter().updateExifMetadataLossy(jpegImageFile, os,
                outputSet);
    }

    private void setStatus(String status, boolean error) {
        statusLabel.setText(status);
        if (error) {
            statusLabel.setForeground(Color.RED);
        } else {
            statusLabel.setForeground(Color.BLACK);
        }
    }

    private Long parseExportTime(JTextField field, JLabel label) {
        try {
            long time = (long) (Double.parseDouble(field.getText()) * 1000000);
            label.setText(formatTime(time));
            return time;
        } catch (NumberFormatException e) {
            label.setText("-");
            return null;
        }
    }

    private boolean validateTimeRange(TrackReaderConfiguration configuration) {
        String errorMsg = null;
        if (exportDataInChartCheckBox.isSelected()) {
            timeStartField.setEnabled(false);
            timeEndField.setEnabled(false);
            timeStartLabel.setText(formatTime((long) (chartRange.getLowerBound() * 1000000) - logReader.getStartMicroseconds()));
            timeEndLabel.setText(formatTime((long) (chartRange.getUpperBound() * 1000000) - logReader.getStartMicroseconds()));
        } else {
            timeStartField.setEnabled(true);
            timeEndField.setEnabled(true);
            Long timeStart;
            Long timeEnd;
            timeStart = parseExportTime(timeStartField, timeStartLabel);
            timeEnd = parseExportTime(timeEndField, timeEndLabel);
            if (timeStart == null || timeEnd == null) {
                errorMsg = "Invalid export time format";
            } else {
                if (timeStart < 0 || timeEnd <= timeStart) {
                    errorMsg = "Invalid export time range";
                } else if (configuration != null) {
                    configuration.setTimeStart(timeStart + logReader.getStartMicroseconds());
                    configuration.setTimeEnd(timeEnd + logReader.getStartMicroseconds());
                }
            }
        }
        if (errorMsg != null) {
            buttonExport.setEnabled(false);
            setStatus(errorMsg, true);
            return false;
        } else {
            buttonExport.setEnabled(true);
            setStatus("Ready to export", false);
            return true;
        }
    }

    private boolean updateConfiguration() {
        String errorMsg = null;

        exporterConfiguration.setImageName(imageNameFormat.getText());
        exporterConfiguration.setStartingNumber(Long.valueOf(imageStartNumber.getText()));

        if (exportDataInChartCheckBox.isSelected()) {
            readerConfiguration.setTimeStart((long) (chartRange.getLowerBound() * 1000000));
            readerConfiguration.setTimeEnd((long) (chartRange.getUpperBound() * 1000000));
        } else {
            if (!validateTimeRange(readerConfiguration)) {
                return false;
            }
        }

        if (errorMsg != null) {
            buttonExport.setEnabled(false);
            setStatus(errorMsg, true);
            return false;
        } else {
            buttonExport.setEnabled(true);
            setStatus("Ready to export", false);
            return true;
        }
    }

    private void updateDialogFromConfiguration() {
        timeStartField.setText(stringFromMicroseconds(readerConfiguration.getTimeStart() - logReader.getStartMicroseconds()));
        timeEndField.setText(stringFromMicroseconds(readerConfiguration.getTimeEnd() - logReader.getStartMicroseconds()));
        logEndTimeValue.setText(String.format(" (log end: %s)", stringFromMicroseconds(logReader.getSizeMicroseconds())));
        validateTimeRange(null);

        imageStartNumber.setText(String.valueOf(exporterConfiguration.getStartingNumber()));
        imageNameFormat.setText(exporterConfiguration.getImageName());
    }

    private void onClose() {
        dispose();
    }

    public void savePreferences(Preferences preferences) {
        PreferencesUtil.saveWindowPreferences(this, preferences.node(DIALOG_SETTING));
        exporterConfiguration.saveConfiguration(preferences.node(EXPORTER_CONFIGURATION_SETTING));
        readerConfiguration.saveConfiguration(preferences.node(READER_CONFIGURATION_SETTING));
        if (lastExportDirectory != null) {
            preferences.put(LAST_EXPORT_DIRECTORY_SETTING, lastExportDirectory.getAbsolutePath());
        }
    }

    public void loadPreferences(Preferences preferences) {
        PreferencesUtil.loadWindowPreferences(this, preferences.node(DIALOG_SETTING), -1, -1);
        exporterConfiguration.loadConfiguration(preferences.node(EXPORTER_CONFIGURATION_SETTING));
        readerConfiguration.loadConfiguration(preferences.node(READER_CONFIGURATION_SETTING));
        String lastExportDirectoryPath = preferences.get(LAST_EXPORT_DIRECTORY_SETTING, null);
        if (null != lastExportDirectoryPath) {
            lastExportDirectory = new File(lastExportDirectoryPath);
        }
    }
}
