package es.andrewazor.dockertest.commands.internal;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.IConnectionHandle;
import org.openjdk.jmc.rjmx.services.jfr.IFlightRecorderService;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

class DownloadCommand extends AbstractCommand {
    DownloadCommand(IFlightRecorderService service, IConnectionHandle handle) {
        super(service, handle);
    }

    @Override
    public String getName() {
        return "download";
    }

    /**
     * First argument is recordingName, second is save path relative to user home.
     */
    @Override
    public void execute(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println(String.format("%s expects two arguments (recording name, save path)", getName()));
        }

        String recordingName = args[0];
        String saveName = args[1];
        Path savePath = Paths.get(System.getProperty("user.home"), saveName);

        IRecordingDescriptor descriptor = null;
        for (IRecordingDescriptor recording : service.getAvailableRecordings()) {
            if (recording.getName().equals(recordingName)) {
                descriptor = recording;
                break;
            }
        }

        if (descriptor == null) {
            System.out.println(String.format("\tCould not locate recording named \"%s\"", recordingName));
            return;
        }

        System.out.println(String.format("\tDownloading recording \"%s\" to \"%s\" ...", recordingName, savePath.toString()));

        Files.copy(service.openStream(descriptor, false), savePath);
    }
}
