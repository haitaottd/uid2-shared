// Copyright (c) 2021 The Trade Desk, Inc
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice,
//    this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package com.uid2.shared.cloud;

import com.uid2.shared.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class EmbeddedResourceStorage implements ICloudStorage {
    private final Path tmpDir = Paths.get("/tmp/uid2");
    private final Class cls;
    private final List<String> resourceList;
    private String urlPrefix;

    public EmbeddedResourceStorage(Class cls) {
        Utils.ensureDirectoryExists(tmpDir);
        this.cls = cls;
        this.resourceList = null;
        this.urlPrefix = null;
    }

    public EmbeddedResourceStorage(Class cls, List<String> resourceList) {
        Utils.ensureDirectoryExists(tmpDir);
        this.cls = cls;
        this.resourceList = Collections.unmodifiableList(resourceList);
        this.urlPrefix = null;
    }

    public EmbeddedResourceStorage withUrlPrefix(String urlPrefix) {
        this.urlPrefix = urlPrefix;
        return this;
    }

    @Override
    public void upload(String localPath, String cloudPath) throws CloudStorageException {
        // this is a read-only cloud storage
        throw new UnsupportedOperationException("EmbeddedResourceStorage::upload method is not supported");
    }

    @Override
    public void upload(InputStream input, String cloudPath) throws CloudStorageException {
        // this is a read-only cloud storage
        throw new UnsupportedOperationException("EmbeddedResourceStorage::upload method is not supported");
    }

    @Override
    public InputStream download(String cloudPath) throws CloudStorageException {
        return cls.getResourceAsStream(cloudPath);
    }

    @Override
    public void delete(String cloudPath) throws CloudStorageException {
        // this is a read-only cloud storage
        throw new UnsupportedOperationException("EmbeddedResourceStorage::delete method is not supported");
    }

    @Override
    public void delete(Collection<String> cloudPath) throws CloudStorageException {
        // this is a read-only cloud storage
        throw new UnsupportedOperationException("EmbeddedResourceStorage::delete method is not supported");
    }

    @Override
    public List<String> list(String prefix) throws CloudStorageException {
        if (this.resourceList == null) {
            File resourceDir = new File(cls.getResource(prefix).getPath());
            return Arrays.stream(resourceDir.listFiles())
                .map(f -> f.getAbsolutePath())
                .collect(Collectors.toList());
        } else {
            return this.resourceList.stream()
                .filter(p -> p.startsWith(prefix))
                .collect(Collectors.toList());
        }
    }

    @Override
    public URL preSignUrl(String cloudPath) throws CloudStorageException {
        try {
            // for embedded resource, pre-signing URL is to download the cloud path to a tmp file and return URL for it
            Path tmpFile = Files.createTempFile(this.tmpDir, "pre-signed", ".dat");
            Files.copy(download(cloudPath), tmpFile, StandardCopyOption.REPLACE_EXISTING);
            if(this.urlPrefix == null || this.urlPrefix.isEmpty()) {
                return tmpFile.toUri().toURL();
            } else {
                return new URL(this.urlPrefix + tmpFile.toString());
            }
        } catch (IOException e) {
            throw new CloudStorageException("EmbeddedResourceStorage::preSignUrl error: " + e.getMessage(), e);
        }
    }

    @Override
    public void setPreSignedUrlExpiry(long expiry) {
        // no-op
    }

    @Override
    public String mask(String cloudPath) {
        return cloudPath;
    }
}
