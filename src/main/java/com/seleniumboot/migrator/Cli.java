package com.seleniumboot.migrator;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Cli {

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("analyze")) {
            System.err.println("usage: selenium-boot-migrator analyze <project-dir>");
            System.exit(2);
        }
        Path dir = Path.of(args[1]);
        if (!Files.isDirectory(dir)) {
            System.err.println("not a directory: " + dir);
            System.exit(2);
        }
        System.out.print(new Analyzer().analyze(dir).render());
    }
}
