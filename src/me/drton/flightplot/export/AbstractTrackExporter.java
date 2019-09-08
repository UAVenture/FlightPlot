package me.drton.flightplot.export;

import me.drton.jmavlib.log.px4.PX4LogReader;

import javax.sound.midi.Track;
import java.io.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Created by ada on 14.01.14.
 */
public abstract class AbstractTrackExporter implements TrackExporter {
    protected TrackReader trackReader;
    protected TrackExporterConfiguration config;
    protected String title;
    protected Writer writer;
    protected int trackPart = 0;
    protected String flightMode = null;

    @Override
    public void export(TrackReader trackReader, TrackExporterConfiguration config, File file, String title) throws IOException {
        this.trackReader = trackReader;
        this.config = config;
        this.writer = new BufferedWriter(new FileWriter(file));
        this.title = title;
        boolean trackStarted = false;
        ArrayList<TrackPoint> setpoints = new ArrayList<TrackPoint>();

        try {
            writeStart();
            while (true) {
                TrackPoint point = trackReader.readNextPoint();
                if (point == null) {
                    break;
                }

                if (point.setpoint) {
                    setpoints.add(point);
                    continue;
                }

                if (!trackStarted || (point.flightMode != null && !point.flightMode.equals(flightMode))) {
                    if (trackStarted) {
                        writePoint(point);  // Write this point at the end of previous track to avoid interruption of track
                        writeTrackPartEnd();
                    }
                    flightMode = point.flightMode;
                    String trackPartName;
                    if (point.flightMode != null) {
                        trackPartName = String.format("%s: %s", trackPart, point.flightMode);
                        trackPart++;
                    } else {
                        trackPartName = "Track";
                    }
                    writeTrackPartStart(trackPartName);
                    trackStarted = true;
                }
                writePoint(point);
            }

            if (trackStarted) {
                writeTrackPartEnd();
            }

            writeSetpoints(setpoints);

            if (trackReader.getLogReader() instanceof PX4LogReader) {
                Map<String, Object> parameters = ((PX4LogReader)trackReader.getLogReader()).getParameters();

                writeSinglePoint(getFromParams(parameters, "1"), "RTL 1");
                writeSinglePoint(getFromParams(parameters, "2"), "RTL 2");
                writeSinglePoint(getFromParams(parameters, "3"), "RTL 3");
                writeSinglePoint(getFromParams(parameters, "4"), "RTL 4");
                writeSinglePoint(getFromParams(parameters, "A"), "RTL A");
                writeSinglePoint(getFromParams(parameters, "B"), "RTL B");
                writeSinglePoint(getFromParams(parameters, "C"), "RTL C");
                writeSinglePoint(getFromParams(parameters, "D"), "RTL D");

            }

            writeEnd();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            this.writer.close();
        }
    }

    private TrackPoint getFromParams(Map<String, Object> parameters, String point) {
        double lat = 0;
        double lon = 0;
        double alt = 0;
        long time = 0;

        Object val = parameters.get(String.format("RTL_P%s_LAT", point));
        if (val != null) {
            lat = ((Number)val).doubleValue();
        }

        val = parameters.get(String.format("RTL_P%s_LON", point));
        if (val != null) {
            lon = ((Number)val).doubleValue();
        }

        val = parameters.get(String.format("RTL_P%s_ALT", point));
        if (val != null) {
            alt = ((Number)val).doubleValue();
        }

        return new TrackPoint(lat, lon, alt, time);
    }

    protected abstract void writeStart() throws IOException;

    protected abstract void writeTrackPartStart(String trackPartName) throws IOException;

    protected abstract void writePoint(TrackPoint point) throws IOException;

    protected abstract void writeTrackPartEnd() throws IOException;

    protected abstract void writeEnd() throws IOException;

    protected void writeSetpoints(List<TrackPoint> setpoints) throws IOException {

    }

    protected abstract void writeSinglePoint(TrackPoint point, String name) throws IOException;
}
