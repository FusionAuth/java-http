# HTTP response design

1. Worker thread runs handler that writes to headers and status to the HTTPResponse
   1. The Server does not participate but continues cycling until bytes are ready to be written
2. Worker thread either calls `close` on the OutputStream to signal it is finished or it starts writing bytes to it
   1. The `close` or first byte written signals to the Server to write back the head section
3. Server writes head section
   1. The Server converts the HTTPResponse to bytes and starts writing
   2. The Server continues writing until the head section is completely written
4. Server writes body chunks that are compressed inline
   1. As the Worker thread writes bytes to the OutputStream, they are compressed inline and written out by the Server

For #1, the Worker thread just does it's work and the Server can keep checking on it by calling a method like `isHeadReady` or `state()`.

For #2, the Server updates the state to `HeadReady` or something like that. This indicates that the Server should convert the HTTPResponse to a head section. Technically, the Worker thread could still modify the HTTPResponse, but that shouldn't be allowed. The HTTPResponse should be complete and the conversion doesn't need to be thread safe.

For #3, the ByteBufferOutputStream can be used because this option doesn't need to be thread safe. The Server can use the resulting ByteBuffer and write it out.

For #4, the Server needs to read chunks and then write them to the Client. The response can optionally be compressed so that `Content-Encoding: gzip` is a header. This should only be done if the request allowed it via the `Accept-Encoding` header.

In order to accomplish all of this, the `HTTPResponse` and `HTTPOutputStream` need to check the `HTTPRequest` to see if the response can be compressed. If it can, then the `HTTPOutputStream` should be wrapped with a `GzipOutputStream`.

The Worker thread then writes to the `OutputStream` and as bytes are written, the Server can periodically read chunks. This needs to be thread safe and synchronized. Here's one approach:

HTTPOutputStream.write uses ByteBuffers that are chunk sized (1024). Once a chunk if full, it can be returned in isolation to the Server. If it isn't full, null is returned. Once a ByteBuffer is full, then it is moved internally from the write side to the read side and a new buffer for the write side is created. This must be synchronized. Similarly, when `close` is called, the write buffer is immediately transferred to the read side. 

