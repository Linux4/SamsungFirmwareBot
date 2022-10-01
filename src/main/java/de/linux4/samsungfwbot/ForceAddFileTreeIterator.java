package de.linux4.samsungfwbot;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.util.FS;

import java.io.File;

public class ForceAddFileTreeIterator extends FileTreeIterator {
    public ForceAddFileTreeIterator(Repository repo) {
        super(repo);
    }

    public ForceAddFileTreeIterator(ForceAddFileTreeIterator parent, File file, FS fs, FileModeStrategy fileModeStrategy) {
        super(parent, file, fs, fileModeStrategy);
    }

    @Override
    public boolean isEntryIgnored() {
        return false;
    }

    @Override
    public boolean walksIgnoredDirectories() {
        return true;
    }

    @Override
    protected AbstractTreeIterator enterSubtree() {
        return new ForceAddFileTreeIterator(this, ((FileEntry) current()).getFile(), fs, fileModeStrategy);
    }
}
