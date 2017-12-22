package kornell;

import org.springframework.mail.javamail.ConfigurableMimeFileTypeMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;


public class S3EventProcessorUnzip implements RequestHandler<S3Event, String> {

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        byte[] buffer = new byte[1024];
        try {
            for (S3EventNotificationRecord record: s3Event.getRecords()) {
                String srcBucket = record.getS3().getBucket().getName();

                // Object key may have spaces or unicode non-ASCII characters.
                String srcKey = record.getS3().getObject().getKey()
                        .replace('+', ' ');
                srcKey = URLDecoder.decode(srcKey, "UTF-8");

                // Detect file type
                Matcher matcher = Pattern.compile(".*\\.([^\\.]*)").matcher(srcKey);
                if (!matcher.matches()) {
                    System.out.println("Unable to detect file type for key " + srcKey);
                    return "";
                }
                String extension = matcher.group(1).toLowerCase();
                if (!"zip".equals(extension)) {
                    System.out.println("Skipping non-zip file " + srcKey + " with extension " + extension);
                    return "";
                }
                System.out.println("Extracting zip file " + srcBucket + "/" + srcKey);
                
                // Download the zip from S3 into a stream
                AmazonS3 s3Client = new AmazonS3Client();
                S3Object s3Object = s3Client.getObject(new GetObjectRequest(srcBucket, srcKey));
                ZipInputStream zis = new ZipInputStream(s3Object.getObjectContent());
                ZipEntry entry = zis.getNextEntry();

                while(entry != null) {
                    String fileName = entry.getName();
                    System.out.println("Extracting " + fileName + ", compressed: " + entry.getCompressedSize() + " bytes, extracted: " + entry.getSize() + " bytes");
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, len);
                    }
                    InputStream is = new ByteArrayInputStream(outputStream.toByteArray());
                    ObjectMetadata meta = new ObjectMetadata();
                    meta.setContentLength(outputStream.size());



                    /*************************************************************
                     * BEGIN WEBSITE SPECIFIC CHANGES
                     *************************************************************/
                    //NOTE: do not set cache for now. This will actually prevent CloudFront from caching not just the broswer.
                    //meta.setCacheControl("max-age=0,s-maxage=0");

                    /* IMPORTANT
                     * Set the ContentType or the AWS Java SDK will default it to application/octet-stream. 
                     * And you will have a bad time
                     *
                     * https://github.com/aws/aws-sdk-java/blob/9243e0716bc2a649085480d3a4fb7606b6541da3/aws-java-sdk-s3/src/main/java/com/amazonaws/services/s3/model/ObjectMetadata.java#L391
                     *
                     * -LB
                     */
                    ConfigurableMimeFileTypeMap mimeMap = new ConfigurableMimeFileTypeMap();
                    String[] newMimeTypes = new String[]{
                      "application/json json",
                      "image/gif gif",
                      "image/jpeg jpeg jpg",
                      "image/png png",
                      "image/svg+xml svg",
                      "image/x-icon ico",
                      "text/css css",
                      "text/html html",
                      "text/javascript js",
                      "text/xml xml",
                      "text/x-handlebars-template handlebars"
                    };
                    mimeMap.setMappings(newMimeTypes);
                    String contentType = mimeMap.getContentType(fileName);
                    meta.setContentType(contentType);
                    /*************************************************************
                     * END WEBSITE SPECIFIC CHANGES
                     *************************************************************/




                    s3Client.putObject(srcBucket, fileName, is, meta);
                    is.close();
                    outputStream.close();
                    entry = zis.getNextEntry();
                }
                zis.closeEntry();
                zis.close();
                
                //delete zip file when done
                s3Client.deleteObject(new DeleteObjectRequest(srcBucket, srcKey));
                System.out.println("Deleted zip file " + srcBucket + "/" + srcKey);
            }
            return "Ok";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
