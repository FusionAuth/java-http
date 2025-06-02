### File Upload



### Use Cases
1. Restrict file uploads  
   - In a multi 
2. Allow uploads all the time
3. Allow uploads, mvc Action dependant
4. Allow uploads, mvc Action dependant, delete on action completion.
   - This means the action is responsible to:
      - Move the file
      - Tell us not to delete it



### Examples

### Spring Boot

- https://github.com/spring-projects/spring-boot/blob/main/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/web/servlet/MultipartProperties.java

`MultipartProperties.java`
```java
/**
 * Whether to enable support of multipart uploads.
 */
private boolean enabled = true;

/**
 * Intermediate location of uploaded files.
 */
private String location;

/**
 * Max file size.
 */
private DataSize maxFileSize = DataSize.ofMegabytes(1);

/**
 * Max request size.
 */
private DataSize maxRequestSize = DataSize.ofMegabytes(10);

/**
 * Threshold after which files are written to disk.
 */
private DataSize fileSizeThreshold = DataSize.ofBytes(0);

/**
 * Whether to resolve the multipart request lazily at the time of file or parameter
 * access.
 */
private boolean resolveLazily = false;
```


#### Summary
- Accept Multi-Part
- See ContentDisposition with a file, collect meta-data about the file, do not create a file yet.
- Uses Servlet handling and "parses" the request if not set to lazily. This functionally does what we are doing, it writes all the files to disk.


io.undertow.server.handlers.form.FormDataParser - has a `close()` method and deletes all files on close. 
