package com.sixtyfour.image.tests;

import com.sixtyfour.image.DalleImageGenerator;
import com.sixtyfour.image.IdeogramImageGenerator;
import com.sixtyfour.image.ImageDimensions;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class AiImageGeneratorTest {

    public static void main(String[] args) throws Exception {

         testImageGenerator();
        // testImageRemix();

/*
        List<String> images = AiImageGenerator.createImages("ai:(random)", false);
        images.forEach(System.out::println);
        */

    }

    private static void testImageGenerator() throws Exception {
        //List<String> images = new DalleImageGenerator().createImages("ai:princess zelda get captured by an evil wizard, b/w, comic panel, line art, sketch drawing with black ink, think lines, anime, very low detail, high contrast, pixel art, white background!", ImageDimensions.SCREEN);
        //images.forEach(System.out::println);

        List<String> images = new IdeogramImageGenerator().createImages("ai:A cat sits in a meadow at noon.  black and white comic drawing, black and white line art, thick lines", ImageDimensions.RETRO);
        images.forEach(System.out::println);
    }

    private static void testImageRemix() throws Exception {
        //List<String> images = new DalleImageGenerator().createImages("ai:princess zelda get captured by an evil wizard, b/w, comic panel,
        // line art, sketch drawing with black ink, think lines, anime, very low detail, high contrast, pixel art, white background!", ImageDimensions.SCREEN);
        //images.forEach(System.out::println);
        byte[] image = Files.readAllBytes(new File("K:\\Video\\Retro\\TI 99-4A Games\\Title.jpg").toPath());
        List<String> images = new IdeogramImageGenerator().createImages("ai:The pyramid from Q*Bert lays onto a TI 99/4A! Q*Bert and some enemies are jumping around on the pyramid", image, 80, ImageDimensions.SCREEN);
        images.forEach(System.out::println);
    }

}
