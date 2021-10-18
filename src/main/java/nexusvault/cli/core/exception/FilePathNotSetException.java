package nexusvault.cli.core.exception;

public class FilePathNotSetException extends AppException {

	private static final long serialVersionUID = 8519301397403828639L;

	public FilePathNotSetException() {
		super();
	}

	public FilePathNotSetException(String message) {
		super(message);
	}

	public FilePathNotSetException(String message, Throwable cause) {
		super(message, cause);
	}

	public FilePathNotSetException(Throwable cause) {
		super(cause);
	}

}
