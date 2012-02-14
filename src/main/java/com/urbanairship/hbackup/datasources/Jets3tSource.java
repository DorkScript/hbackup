package com.urbanairship.hbackup.datasources;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.model.StorageObject;

import com.urbanairship.hbackup.Constant;
import com.urbanairship.hbackup.HBFile;
import com.urbanairship.hbackup.HBackupConfig;
import com.urbanairship.hbackup.Source;
import com.urbanairship.hbackup.Stats;

public class Jets3tSource extends Source {
    private static final Logger log = LogManager.getLogger(Jets3tSource.class);
    
//    private final URI baseUri;
//    private final HBackupConfig conf;
//    private final AWSCredentials awsCreds;
    private final S3Service s3Service;
    private final String bucketName;
    private final String baseName;
    private final Stats stats;
    
    public Jets3tSource(URI uri, HBackupConfig conf, Stats stats) throws IOException {
//        this.baseUri = uri;
//        this.conf = conf;
        this.stats = stats;
        this.bucketName = uri.getHost();
        String uriPath = uri.getPath();
        if(uriPath.startsWith("/")) {
            this.baseName = uriPath.substring(1);            
        } else {
            this.baseName = uriPath;
        }
        
//        AWSCredentials awsCreds = new AWSCredentials(conf.sinkS3AccessKey, conf.sinkS3Secret);
        try {
            s3Service = new RestS3Service(conf.s3SourceCredentials);
        } catch (S3ServiceException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<HBFile> getFiles(boolean recursive) throws IOException {
        String prefix = baseName.endsWith("/") ? baseName : baseName + "/"; // Ensure prefix ends with "/"
        List<HBFile> outFiles = new ArrayList<HBFile>();
        try {
            S3Object[] listing = s3Service.listObjects(bucketName, prefix, null);
            for(S3Object s3Obj: listing) {
                if(s3Obj.getContentLength() > 0) {
                    // Get the "file name" relative to the hbackup source "directory"
                    String relativePath = s3Obj.getKey().substring(baseName.length());
                    if(relativePath.startsWith("/")) {
                        relativePath = relativePath.substring(1);
                    }
                    outFiles.add(new Jets3tSourceFile(s3Obj, relativePath));
                }
            }
            return outFiles;
        } catch (S3ServiceException e) {
            throw new IOException(e);
        }
    }
    
    private class Jets3tSourceFile extends HBFile {
        private final S3Object s3Obj;
        private final String relativePath;
        
        public Jets3tSourceFile(S3Object s3Obj, String relativePath) {
            this.s3Obj = s3Obj; 
            this.relativePath = relativePath;
            assert !relativePath.startsWith("/");
        }
        
        @Override
        public InputStream getFullInputStream() throws IOException {
            try {
                S3Object completeObj = s3Service.getObject(bucketName, s3Obj.getKey());
                return completeObj.getDataInputStream();
            } catch (ServiceException e) {
                throw new IOException(e);
            }
        }
        
        @Override
        public InputStream getPartialInputStream(long offset, long len) throws IOException {
            try {
                // The end of the byte range is "offset+len-1" because it's end-inclusive
                S3Object completeObj = s3Service.getObject(bucketName, s3Obj.getKey(), null,
                        null, null, null, offset, offset + len - 1);
                return completeObj.getDataInputStream();
            } catch (ServiceException e) {
                throw new IOException(e);
            }
        }
    
        @Override
        public String getRelativePath() {
            return relativePath;
        }
    
        @Override
        public long getMTime() throws IOException {
//            return new DateTime(s3Obj.getLastModifiedDate(), DateTimeZone.UTC).getMillis();
            try {
                StorageObject detailsObj = s3Service.getObjectDetails(s3Obj.getBucketName(), s3Obj.getKey());
                Object metadataObj = detailsObj.getMetadata(Constant.S3_SOURCE_MTIME);
                if(metadataObj == null) {
                    // Fall back to the S3 last-modified time if no source mtime metadata exists
                    log.debug("Source object had no source mtime metadata, falling back to last-modified time");
                    return s3Obj.getLastModifiedDate().getTime();
                }
                if(!(metadataObj instanceof String)) {
                    log.debug("Source object mtime metadata should have been a string but was " +
                            metadataObj + ", falling back to last-modified time for " + s3Obj.getKey());
                    return s3Obj.getLastModifiedDate().getTime();
                }
                try {
                    return Long.valueOf((String)detailsObj.getMetadata(Constant.S3_SOURCE_MTIME));
                } catch (NumberFormatException e) {
                    log.debug("Source object mtime metadata couldn't be parsed for " + s3Obj.getKey() +
                            ", was " + metadataObj + ". Falling back to last-modified time");
                    return s3Obj.getLastModifiedDate().getTime();
                }
                
            } catch (ServiceException e) {
                throw new IOException(e);
            }
        }
    
        @Override
        public long getLength() {
            return s3Obj.getContentLength();
        }

    }
}
