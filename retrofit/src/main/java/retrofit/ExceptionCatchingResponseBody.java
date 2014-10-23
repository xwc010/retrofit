package retrofit;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.ResponseBody;
import java.io.IOException;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

class ExceptionCatchingResponseBody extends ResponseBody {
  private final ResponseBody delegate;
  private final ExceptionCatchingSource delegateSource;

  ExceptionCatchingResponseBody(ResponseBody delegate) throws IOException {
    this.delegate = delegate;
    this.delegateSource = new ExceptionCatchingSource(delegate.source());
  }

  @Override public MediaType contentType() {
    return delegate.contentType();
  }

  @Override public long contentLength() {
    return delegate.contentLength();
  }

  @Override public BufferedSource source() {
    return Okio.buffer(delegateSource);
  }

  IOException getThrownException() {
    return delegateSource.thrownException;
  }

  boolean threwException() {
    return delegateSource.thrownException != null;
  }

  private static class ExceptionCatchingSource extends ForwardingSource {
    private IOException thrownException;

    ExceptionCatchingSource(Source delegate) {
      super(delegate);
    }

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      try {
        return super.read(sink, byteCount);
      } catch (IOException e) {
        thrownException = e;
        throw e;
      }
    }

    @Override public void close() throws IOException {
      try {
        super.close();
      } catch (IOException e) {
        thrownException = e;
        throw e;
      }
    }
  }
}
