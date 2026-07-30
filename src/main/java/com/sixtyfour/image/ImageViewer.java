package com.sixtyfour.image;

import javax.servlet.ServletConfig;
import javax.servlet.ServletOutputStream;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Servlet to download an image/image list
 *
 * @author EgonOlsen
 */
@WebServlet(name = "ImageViewer", urlPatterns = {"/ImageViewer"}, initParams = {
        @WebInitParam(name = "imagepath", value = "/imagedata/")})
public class ImageViewer extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private ImageViewerService service = new ImageViewerService();

    public ImageViewer() {
        // TODO Auto-generated constructor stub
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        doGet(request, response);
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        service.setUserAgent();
        ServletConfig sc = getServletConfig();
        String path = sc.getInitParameter("imagepath");

        response.setHeader("WiC64", "true");
        response.setContentType("application/octet-stream");
        ServletOutputStream os = response.getOutputStream();
        os.flush();

        String clear = request.getParameter("clear");
        if (clear != null) {
            ImageCache.clear();
        }

        String file = URLDecoder.decode(request.getParameter("file"), StandardCharsets.UTF_8).trim();
        boolean needsCropping = false;
        boolean d42Mode = false;

        if (service.URL_SHORTENER.containsKey(file)) {
            needsCropping = file.contains("ai=1");
            d42Mode = file.contains("d42=1");
            Logger.log("Replacing URL " + file + " with " + service.URL_SHORTENER.get(file));
            file = service.URL_SHORTENER.get(file);
        }

        boolean hires = request.getParameter("hi")!=null && request.getParameter("hi").equals("1");

        if (hires) {
            Logger.log("Hires mode enabled!");
        }

        if (file.startsWith("empty:")) {
            Logger.log("Sending empty reply!");
            os.flush();
            return;
        }

        String dither = request.getParameter("dither");
        boolean keepRatio = Boolean.parseBoolean(request.getParameter("ar"));
        if (file.contains("..") || file.contains("\\") || file.startsWith("/")) {
            Logger.log("Invalid file name: " + file);
            service.printError(os, "Invalid file name!");
            return;
        }
        float dithy = 1;
        if (dither != null) {
            try {
                dithy = Float.parseFloat(dither) / 100f;
                dithy = Math.min(1, Math.max(0, dithy));
            } catch (Exception e) {
                //
            }
        }
        Logger.log("Dithering is set to " + dithy);

        String key = ImageCache.getKey(file, dithy, keepRatio, hires);
        Blob blob = ImageCache.get(key);
        if (blob == null) {
            blob = service.convert(file, path, os, dithy, keepRatio, needsCropping, d42Mode, hires);
            if (blob == null) {
                // No image but a file list...
                return;
            }
            ImageCache.put(key, blob);
            if (blob.isError()) {
                // The actual error has already been transmitted by the convert()-method
                return;
            }
        }
        if (blob.isError()) {
            // Cached error, re-transmit it...
            service.printError(os, blob.getError());
            return;
        }

        try (InputStream is = blob.getAsStream()) {
            response.setHeader("Content-disposition",
                    "attachment; filename=" + blob.getTarget());
            // Transfer whole blob...
            is.transferTo(os);
        } catch (Exception e) {
            Logger.log("Failed to transfer file: " + blob.getSource(), e);
            return;
        } 
        os.flush();
        Logger.log("Download and conversion finished!");
    }
}