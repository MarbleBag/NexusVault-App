package nexusvault.cli.plugin.export;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import kreed.io.util.ByteBufferBinaryReader;
import nexusvault.archive.IdxFileLink;
import nexusvault.archive.IdxPath;
import nexusvault.archive.NexusArchive;
import nexusvault.archive.util.DataHeader;
import nexusvault.cli.core.App;
import nexusvault.cli.core.extension.AbstractExtension;
import nexusvault.cli.extensions.archive.ArchiveExtension;
import nexusvault.cli.extensions.archive.NexusArchiveContainer;
import nexusvault.cli.plugin.export.model.ModelExporter;
import nexusvault.cli.plugin.export.tex.TextureExporter;

public final class ExportPlugIn extends AbstractExtension {

	private static final Logger logger = LogManager.getLogger(ExportPlugIn.class);

	private static interface ExportLoader {
		void export(ExportConfig config) throws IOException;

		String getSourceName();
	}

	private static abstract class AbstExportLoader {
		protected final DataLocator dataLocator;

		public AbstExportLoader(DataLocator dataLocator) {
			this.dataLocator = dataLocator;
		}

		public final String getSourceName() {
			return this.dataLocator.getDataLocation().getFullName();
		}
	}

	private final class DynamicExportLoader extends AbstExportLoader implements ExportLoader {
		public DynamicExportLoader(DataLocator dataLocator) {
			super(dataLocator);
		}

		@Override
		public void export(ExportConfig config) throws IOException {
			final Path outputFolder = getOutputFolder();
			final ByteBuffer data = this.dataLocator.getData();
			final Exporter exporter = findExporter(data, getFileEnding());
			final IdxPath dataPath = this.dataLocator.getDataLocation();
			exporter.export(outputFolder, data, dataPath);
		}

		private String getFileEnding() {
			final IdxPath path = this.dataLocator.getDataLocation();
			final String lastElement = path.getLastName();
			final int idx = lastElement.lastIndexOf(".");
			if (idx > 0) {
				return lastElement.substring(idx + 1);
			} else {
				return null;
			}
		}
	}

	private final class StaticExportLoader extends AbstExportLoader implements ExportLoader {

		private final Exporter exporter;

		private StaticExportLoader(Exporter exporter, DataLocator dataLocator) {
			super(dataLocator);
			this.exporter = exporter;
		}

		@Override
		public void export(ExportConfig config) throws IOException {
			final Path outputFolder = getOutputFolder();
			final ByteBuffer data = this.dataLocator.getData();
			final IdxPath dataPath = this.dataLocator.getDataLocation();
			this.exporter.export(outputFolder, data, dataPath);
		}
	}

	protected static interface DataLocator {
		ByteBuffer getData() throws IOException;

		IdxPath getDataLocation();
	}

	private static class IdxDataLocator implements DataLocator {
		private final IdxFileLink fileLink;

		public IdxDataLocator(IdxFileLink fileLink) {
			this.fileLink = fileLink;
		}

		@Override
		public ByteBuffer getData() throws IOException {
			return this.fileLink.getData();
		}

		@Override
		public IdxPath getDataLocation() {
			return this.fileLink.getPath();
		}

	}

	private static class PathDataLocator implements DataLocator {
		private final Path path;

		public PathDataLocator(Path path) {
			this.path = path;
		}

		@Override
		public ByteBuffer getData() throws IOException {
			try (SeekableByteChannel channel = Files.newByteChannel(this.path, StandardOpenOption.READ)) {
				final ByteBuffer data = ByteBuffer.allocate((int) channel.size()).order(ByteOrder.LITTLE_ENDIAN);

				int numberOfReadbytes = 0;
				int counter = 0;
				do {
					numberOfReadbytes = channel.read(data);
					if (numberOfReadbytes == -1) {
						break;
					} else if (numberOfReadbytes == 0) {
						if (!data.hasRemaining()) {
							break;
						} else {
							counter += 1;
							if (counter > 100) {
								break; // we will have to work with what we got - for now
							}
						}
					} else {
						counter = 0;
					}
				} while (true);
				return data.flip();
			}
		}

		@Override
		public IdxPath getDataLocation() {
			return IdxPath.createPath(this.path.getFileName().toString());
		}
	}

	public static final class ExportConfig {

		private boolean exportAsBinary;

		public void exportAsBinary(boolean value) {
			this.exportAsBinary = value;
		}

		public boolean isExportAsBinaryChecked() {
			return this.exportAsBinary;
		}

	}

	private static final class ExportError {
		public final String path;
		public final IdxFileLink link;
		public final Exception error;

		public ExportError(IdxFileLink link, Exception error) {
			super();
			this.path = null;
			this.link = link;
			this.error = error;
		}

		public ExportError(String path, Exception error) {
			super();
			this.path = path;
			this.link = null;
			this.error = error;
		}

		public String getTarget() {
			if (this.link == null) {
				return this.path;
			}
			return this.link.getFullName();
		}
	}

	private List<Exporter> exporters = new ArrayList<>();
	private ExecutorService threadPool;

	private void loadExporters(List<Exporter> exporters) { // TODO
		exporters.add(new ModelExporter());
		exporters.add(new TextureExporter());
		exporters.add(new TBL2CSVExporter());
		exporters.add(new FontExporter());
		exporters.add(new TextExporter());
		exporters.add(new LocaleExporter());
	}

	public void exportIdxPath(List<IdxPath> filesToExport, ExportConfig someConfig) {
		exportIdxFileLink(findFiles(filesToExport), someConfig);
	}

	public void exportIdxFileLink(List<IdxFileLink> filesToExport, ExportConfig someConfig) {
		exportDataLoader(filesToExport.stream().map(IdxDataLocator::new), someConfig);
	}

	public void exportPath(List<Path> filesToExport, ExportConfig someConfig) {
		exportDataLoader(filesToExport.stream().map(PathDataLocator::new), someConfig);
	}

	private void exportDataLoader(Stream<DataLocator> filesToExport, ExportConfig someConfig) {
		if (someConfig.isExportAsBinaryChecked()) {
			final BinaryExporter binaryExporter = new BinaryExporter(Collections.emptyList());
			export(filesToExport.map(f -> new StaticExportLoader(binaryExporter, f)), someConfig);
		} else {
			export(filesToExport.map(DynamicExportLoader::new), someConfig);
		}
	}

	private void export(Stream<ExportLoader> filesToExport, ExportConfig someConfig) {
		final Collection<ExportError> exportErrors = new ConcurrentLinkedQueue<>();

		export(filesToExport, someConfig, exportErrors);

		if (!exportErrors.isEmpty()) {
			sendMsg(() -> {
				final StringBuilder msg = new StringBuilder();
				msg.append(String.format("Unable to export %d file(s)\n", exportErrors.size()));
				msg.append("Error(s)\n");
				for (final var error : exportErrors) {
					msg.append(error.getTarget()).append("\n");
					msg.append("->").append(error.error.getClass());
					msg.append(" : ").append(error.error.getMessage());
					msg.append("\n");

					var cause = error.error.getCause();
					while (cause != null) {
						msg.append("\t\t->").append(cause.getClass()).append(" : ").append(cause.getMessage()).append("\n");
						cause = cause.getCause();
					}
				}
				return msg.toString();
			});

			logger.error(String.format("Unable to export %d file(s)\n", exportErrors.size()));
			for (final var error : exportErrors) {
				logger.error(error.getTarget(), error.error);
			}
		}
	}

	private void export2(Stream<ExportLoader> filesToExport, ExportConfig someConfig, final Collection<ExportError> exportErrors) {
		final List<ExportLoader> oof = filesToExport.collect(Collectors.toList());
		final int numberOfFilesToUnpack = oof.size();
		final int reportAfterNFiles = Math.max(1, Math.min(500, numberOfFilesToUnpack / 20));

		sendMsg(() -> "Start exporting to: " + getOutputFolder());

		int seenFiles = 0;
		int reportIn = 0;
		final long exportAllStart = System.currentTimeMillis();

		for (final ExportLoader exportLoader : oof) {
			try {
				exportLoader.export(someConfig);
			} catch (final Exception e) {
				exportErrors.add(new ExportError(exportLoader.getSourceName(), e));
			}

			seenFiles += 1;
			reportIn += 1;
			if (reportIn >= reportAfterNFiles) {
				final float percentage = seenFiles / (numberOfFilesToUnpack + 0f) * 100;
				final String msg = String.format("Processed files %d of %d (%.2f%%).", seenFiles, numberOfFilesToUnpack, percentage);
				sendMsg(msg);
				reportIn = 0;
			}
		}

		final long exportAllEnd = System.currentTimeMillis();
		sendMsg(() -> String.format("Processed files %1$d of %1$d (100%%).", numberOfFilesToUnpack));
		sendMsg(() -> String.format("Exporting done in %.2fs", (exportAllEnd - exportAllStart) / 1000f));
	}

	private void export(Stream<ExportLoader> filesToExport, ExportConfig someConfig, final Collection<ExportError> exportErrors) {
		final var oof = filesToExport.collect(Collectors.toList());
		final var numberOfFilesToUnpack = oof.size();

		sendMsg(() -> "Start exporting to: " + getOutputFolder());

		final var tasks = new LinkedList<Future<?>>();
		final var exportAllStart = System.currentTimeMillis();

		for (final ExportLoader exportLoader : oof) {
			final var task = this.threadPool.submit(() -> {
				try {
					exportLoader.export(someConfig);
				} catch (final Exception e) {
					exportErrors.add(new ExportError(exportLoader.getSourceName(), e));
				}
			});

			tasks.add(task);
		}

		reportToUserAndWait(tasks);

		final long exportAllEnd = System.currentTimeMillis();
		sendMsg(() -> String.format("Processed files %1$d of %1$d (100%%).", numberOfFilesToUnpack));
		sendMsg(() -> String.format("Export done in %.2fs", (exportAllEnd - exportAllStart) / 1000f));
	}

	private void reportToUserAndWait(List<Future<?>> tasks) {
		final var numberOfFilesToUnpack = tasks.size();
		final var reportAfterNFiles = Math.max(1, Math.min(500, numberOfFilesToUnpack / 20));

		int processedFiles = 0;
		int reportIn = 0;
		while (!tasks.isEmpty()) {
			final var iterator = tasks.iterator();
			while (iterator.hasNext()) {
				final var task = iterator.next();
				if (task.isDone()) {
					iterator.remove();
					++reportIn;
				}
			}

			if (reportIn >= reportAfterNFiles) {
				processedFiles += reportIn;
				reportIn = 0;
				final float percentage = processedFiles / (numberOfFilesToUnpack + 0f) * 100;
				final String msg = String.format("Processed files %d of %d (%.2f%%).", processedFiles, numberOfFilesToUnpack, percentage);
				sendMsg(msg);
			}

			if (tasks.size() > 100) {
				try {
					Thread.sleep((int) (tasks.size() * 0.2));
				} catch (final InterruptedException e) {
				}
			} else {
				Thread.yield();
			}
		}
	}

	private List<IdxFileLink> findFiles(List<IdxPath> paths) {
		final var archiveExtension = App.getInstance().getExtension(ArchiveExtension.class);
		final List<NexusArchiveContainer> wrappers = archiveExtension.getArchives();
		if (wrappers.isEmpty()) {
			sendMsg("No vaults are loaded. Use 'help' to learn how to load them");
			return Collections.emptyList();
		}

		final List<IdxFileLink> fileToExport = new ArrayList<>(paths.size());
		for (final IdxPath path : paths) {
			for (final NexusArchiveContainer wrapper : wrappers) {
				final NexusArchive archive = wrapper.getArchive();
				if (path.isResolvable(archive.getRootDirectory())) {
					fileToExport.add(path.resolve(archive.getRootDirectory()).asFile());
					break;
				}
			}
		}

		return fileToExport;
	}

	private Exporter findExporter(ByteBuffer data, String fileEnding) {
		final DataHeader dataHeader = new DataHeader(new ByteBufferBinaryReader(data));
		Exporter exporter;
		if (fileEnding != null) {
			exporter = findExporter(dataHeader, fileEnding);
			if (exporter == null) {
				throw new NoExporterFoundException("File type '" + fileEnding + "' not supported.");
			}
		} else {
			exporter = findExporter(dataHeader);
			if (exporter == null) {
				throw new NoExporterFoundException();
			}
		}
		return exporter;
	}

	private Exporter findExporter(ByteBuffer data) {
		return findExporter(data, null);
	}

	private Exporter findExporter(DataHeader dataHeader) {
		for (final Exporter exporter : getExporters()) {
			if (exporter.accepts(dataHeader)) {
				return exporter;
			}
		}
		return null;
	}

	private Exporter findExporter(DataHeader dataHeader, String fileEnding) {
		fileEnding = fileEnding.toLowerCase();
		for (final Exporter exporter : getExporters()) {
			final Set<String> acceptedFileEndings = exporter.getAcceptedFileEndings();
			if (acceptedFileEndings.contains(fileEnding) && exporter.accepts(dataHeader)) {
				return exporter;
			}
		}
		return null;
	}

	protected List<Exporter> getExporters() {
		return this.exporters;
	}

	public Path getOutputFolder() {
		return App.getInstance().getAppConfig().getOutputPath();
	}

	@Override
	protected void initializeExtension(InitializationHelper initializationHelper) {
		initializationHelper.addCommandHandler(new ExportCmd());
		initializationHelper.addCommandHandler(new ExportFileCmd());

		loadExporters(this.exporters);
		this.exporters = Collections.unmodifiableList(this.exporters);
		for (final var exporter : this.exporters) {
			exporter.initialize();
		}

		this.threadPool = Executors.newWorkStealingPool(); // runs as many threads as cores are available
	}

	@Override
	protected void deinitializeExtension() {
		for (final var exporter : this.exporters) {
			exporter.deinitialize();
		}
		this.exporters.clear();

		this.threadPool.shutdownNow();
	}

}
