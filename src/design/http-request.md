# HTTP request design

1. Client writes start-line
   1. Server reads bytes
   2. Server processes all bytes until end of head section
   3. Server goes back to reading using same buffer if more head section is expected. Otherwise, it goes into body piping mode and executes using Worker thread
2. Client writes headers
   1. Server reads bytes
   2. Server processes all bytes until end of head section
   3. Server goes back to reading using same buffer if more head section is expected. Otherwise, it goes into body piping mode and executes using Worker thread
3. Client writes body
   1. Server pipes body bytes to Worker thread somehow

#1 and #2 above can use a simple ByteBuffer since it doesn't need to be thread safe and can be re-used. In fact, it can use a single ByteBuffer for all operations since there is only a single Server thread. The request parser needs to handle characters inline and when it completes the head section, it must hand off the remaining bytes in the buffer to the thread safe pipe for #3.

#3 needs to use a thread safe pipe of some sort. This allows us to start the execute thread and then let the processing begin in a streaming way. The pipe for this needs to block on the read side and not on the write side.

* [x] Implement preamble parser
* [x] Implement server to worker pipe such that it blocks on the reader side
* [x] Connect to NIO selector