package com.aliucord.plugins;

import java.util.ArrayList;
import java.util.List;

public class AttachmentBody {

    List<File> files = new ArrayList<>();

    public AttachmentBody(String filename, long size) {
        files.add(new File(filename, size));

    }

    public class File {
        String filename;
        long file_size;
        int id = 0;
        public File(String filename, long size) {
            this.filename = filename;
            this.file_size = size;
        }
    }
}
