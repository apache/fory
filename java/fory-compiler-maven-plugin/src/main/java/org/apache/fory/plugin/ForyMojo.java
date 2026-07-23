package org.apache.fory.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

@Mojo(name = "compile")
public class ForyMojo extends AbstractMojo {

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("🔥 Fory Compiler Plugin is running...");
    }
}