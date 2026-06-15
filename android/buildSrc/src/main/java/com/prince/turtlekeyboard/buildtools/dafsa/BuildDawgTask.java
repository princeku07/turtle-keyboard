package com.prince.turtlekeyboard.buildtools.dafsa;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;

/**
 * Gradle task wrapper around {@link DafsaBuilder}. Up-to-date if the source
 * unigram file and the output dawg are both unchanged — avoids rebuilding
 * the ~1 MB asset on every incremental build.
 */
public abstract class BuildDawgTask extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getInput();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    @TaskAction
    public void build() throws IOException {
        DafsaBuilder.build(
                getInput().get().getAsFile().toPath(),
                getOutput().get().getAsFile().toPath());
    }
}
