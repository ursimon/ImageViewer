package com.sixtyfour.image;

import com.sixtyfour.petscii.HiEddiConverter;
import com.sixtyfour.petscii.KoalaConverter;
import com.sixtyfour.petscii.Vic2Colors;

import javax.net.ssl.SSLHandshakeException;
import java.io.*;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ImageViewerService {

    public final static LinkedHashMap<String, String> URL_SHORTENER = new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry eldest) {
            return this.size() > 2000;
        }
    };

    private final static LinkedHashMap<String, Blob> AI_REQUEST_CACHE = new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry eldest) {
            Map.Entry<String, Blob> entry = eldest;
            return System.currentTimeMillis() - entry.getValue().getTime() > 1000 * 20 || this.size() > 1000;
        }
    };

    public void setUserAgent() {
        System.setProperty("http.agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36");
        System.setProperty("https.agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36");
    }

    public Blob convert(String file, String path, OutputStream os, float dithy, boolean keepRatio, boolean needsCropping, boolean d42Mode, boolean hires) {
        Blob blob;
        boolean directPdfLink = file.startsWith("page://");
        boolean maybeUrl = UrlUtils.maybeUrl(file);

        if (!directPdfLink) {
            if (file.endsWith(".")) {
                file = file.substring(0, file.length() - 1);
            }
            if (maybeUrl && !file.toLowerCase().startsWith("http")) {
                file = "https://" + file;
            }

            file = UrlUtils.fixFilename(file);

            String lfile = file.toLowerCase();
            if (!lfile.contains(".png") && !lfile.contains(".jpg") && !lfile.contains(".jpeg") && !lfile.contains(".webp")) {
                Logger.log("Unsupported image type: " + file);
                if (lfile.contains(".pdf")) {
                    Logger.log("PDF detected, rendering it...");
                    List<String> rendered = new PdfRenderer().renderPages(file, path);
                    transmitImageReferences(os, rendered, null, false, hires, null);
                } else {
                    if (maybeUrl) {
                        Logger.log("Trying to extract images from page...");
                        extractImages(file, os, ImageMode.WEB, hires);
                    } else {
                        if (UrlUtils.isAiPrompt(lfile)) {
                            Logger.log("Generating images with AI...");
                            extractImages(file, os, ImageMode.AI, hires);
                        } else {
                            Logger.log("Searching for images on Google...");
                            extractImages(file, os, ImageMode.SEARCH, hires);
                        }
                    }
                }
                return null;
            }
        }

        Logger.log("Downloading image: " + file);

        String ext = getType(file);

        String targetFile = UUID.randomUUID() + ext;
        File pathy = new File(path);
        boolean ok = pathy.mkdirs();
        Logger.log("Directory state: " + ok);
        File bin = new File(pathy, targetFile);

        file = UrlUtils.encode(file); // (Re-)encode the URL..not sure, why I'm decoding it in the first place, but anyway...

        setUserAgent();
        try (InputStream input = directPdfLink ? new FileInputStream(new File(pathy, file.substring(7))) : new URL(file).openStream(); FileOutputStream fos = new FileOutputStream(bin)) {
            input.transferTo(fos);
        } catch (FileNotFoundException e) {
            Logger.log("File not found: " + file, e);
            delete(bin);
            return printError(os, "Image not found!");
        } catch (IOException e) {
            Logger.log("IO error while loading image: " + file, e);
            String code = "???";
            String msg = e.getMessage();
            if (msg.contains("code: ")) {
                int pos = msg.indexOf("code: ");
                code = msg.substring(pos + 6, pos + 9).trim();
            }
            delete(bin);
            return printError(os, "Server returned error: " + code);
        } catch (Exception e) {
            Logger.log("Failed to load image: " + file, e);
            delete(bin);
            return printError(os, "Failed to load image!");
        }

        String fileName = bin.toString();
        String targetFileName = fileName + ".koa";
        File targetBin = new File(targetFileName);
        try {
            if (hires) {
                Logger.log("Hires image conversion ...");
                HiEddiConverter.convert(fileName, targetFileName, new Vic2Colors(), 1, dithy, keepRatio, needsCropping, d42Mode, false);
            } else {
                Logger.log("Multicolor image conversion ...");
                KoalaConverter.convert(fileName, targetFileName, new Vic2Colors(), 1, dithy, keepRatio, needsCropping, d42Mode, false);
            }
        } catch (Exception e) {
            delete(targetBin);
            delete(bin);
            Logger.log("Failed to convert image: " + file, e);
            if (e.getMessage() != null) {
                return printError(os, e.getMessage());
            } else {
                return printError(os, "Failed to convert image!");
            }
        }

        blob = new Blob(targetFile, file);
        try (FileInputStream fis = new FileInputStream(targetBin)) {
            // Store file data in blob...
            blob.fill(fis);
        } catch (Exception e) {
            Logger.log("Failed to fill blob: " + file, e);
            blob = printError(os, "Cache error!");
        } finally {
            delete(targetBin);
            delete(bin);
        }
        return blob;
    }

    public Blob printError(OutputStream os, String text) {
        try {
            os.write(0);
            os.write(0);
            os.write(("ERROR: " + text).getBytes(StandardCharsets.UTF_8));
            return new Blob(text);
        } catch (Exception e) {
            Logger.log("Failed to write error into stream!", e);
            return null;
        }
    }

    private String getType(String file) {
        String lFile = file.toLowerCase();
        if (lFile.contains(".jpg") || lFile.contains(".jpeg")) {
            return ".jpg";
        }
        if (lFile.contains(".png")) {
            return ".png";
        }
        if (lFile.contains(".webp")) {
            return ".webp";
        }
        return lFile.substring(file.lastIndexOf("."));
    }

    private void extractImages(String query, OutputStream os, ImageMode mode, boolean hires) {
        List<String> images = null;
        boolean d42Mode = false;
        try {
            if (mode==ImageMode.WEB) {
                ImageExtractor iex = new ImageExtractor();
                try {
                    images = iex.extractImages(query);
                } catch (SSLHandshakeException e) {
                    Logger.log("https doesn't work, trying http instead...");
                    images = iex.extractImages(query.replace("https:", "http:"));
                } catch (IgnoredRedirectException ee) {
                    Logger.log("trying with/out www....");
                    if (query.contains("www.")) {
                        Logger.log("Removing www...");
                        images = iex.extractImages(query.replace("www.", ""));
                    } else {
                        Logger.log("Adding www...");
                        images = iex.extractImages(query.replace("://", "://www."));
                    }
                }
            }
            if (mode == ImageMode.SEARCH) {
                images = GoogleImageExtractor.searchImages(query);
            }

            if (mode == ImageMode.AI) {
                if (query.contains("(d42)") || query.contains("(D42)")) {
                    query = query.replace("(d42)", " ").replace("(D42)", " ").trim();
                    d42Mode = true;
                    Logger.log("D42 mode enabled!");
                }
                Blob blobby = AI_REQUEST_CACHE.get(query);
                images = null;
                if (blobby != null) {
                    Logger.log("Found entry for "+query+" in request cache ("+AI_REQUEST_CACHE.size()+"), age "+blobby.getAge()+" seconds!");
                    if (blobby.isOld(20000)) {
                        Logger.log("...but it's too old!");
                        AI_REQUEST_CACHE.remove(query);
                    } else {
                        images = blobby.getImages();
                    }
                }
                if (images == null) {
                    Logger.log("Found no entry for "+query+" in request cache!");
                    images = AiDecider.generateImages(query, d42Mode);
                }
            }
        } catch (AiException e) {
            Logger.log("Invalid query: " + query, e);
            printError(os, e.getMessage().replace("_", " "));
            return;
        } catch (FileNotFoundException e) {
            Logger.log("URL not found: " + query, e);
            printError(os, "URL not found!");
            return;
        } catch (UnknownHostException e) {
            Logger.log("Unknown host: " + query, e);
            printError(os, "Unknown host!");
            return;
        } catch (java.net.SocketException e) {
            Logger.log("Network is unreachable: " + query, e);
            printError(os, "Network is unreachable (local?)!");
            return;
        } catch (Exception e) {
            Logger.log("Failed to extract images from " + query, e);
            printError(os, "No valid images found!");
            return;
        }

        transmitImageReferences(os, images, mode, d42Mode, hires, query);
    }

    private void transmitImageReferences(OutputStream os, List<String> images, ImageMode mode, boolean d42Mode, boolean hires, String query) {
        if (images == null) {
            printError(os, "No valid images found!");
            return;
        }

        if (mode == ImageMode.AI) {
            Logger.log("Storing images as result for "+query+" in request cache!");
            if (!AI_REQUEST_CACHE.containsKey(query)) {
                AI_REQUEST_CACHE.put(query, new Blob(images));
            } else {
                Logger.log("Request cache already contains an entry for "+query);
            }
        }

        for (int i = 0; i < images.size(); i++) {
            String image = images.get(i);
            if (image.length() < 170 && mode != ImageMode.AI) {
                continue;
            }
            String postFix = "";
            if (d42Mode) {
                postFix = "d42=1";
            } else if (mode==ImageMode.AI) {
                postFix = "ai=1";
            }
            if (hires) {
                postFix+="hi=1";
            }

            String newImage = "https://jpct.de/" + UUID.randomUUID() + ".short"+postFix;
            Logger.log("URL too long, transmitting a short form instead!");
            URL_SHORTENER.put(newImage, image);
            images.set(i, newImage);
        }

        if (images.isEmpty()) {
            printError(os, "No valid images found!");
            return;
        }

        //
		/*
			Convert image list into bytes...Format is:
			1 1
			length - bytes
			length - bytes
			...
			0
		 */
        if (images.size() > 22) {
            images = images.subList(0, 22);
            Logger.log("Limited image list to " + images.size());
        }
        try(ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            bos.write(new byte[]{1, 1}); // Flag image list to C64

            byte[] len = new byte[1];
            for (String img : images) {
                byte[] txt = img.getBytes(StandardCharsets.US_ASCII);
                len[0] = (byte) (txt.length & 0xff);
                bos.write(len);
                bos.write(txt);
            }
            len[0] = 0;
            bos.write(len);
            ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
            bis.transferTo(os);
        } catch (Exception e) {
            Logger.log("Failed to process image list!", e);
            printError(os, "Failed to process images!");
        }
    }

    private void delete(File bin) {
        Logger.log("Deleting file: " + bin);
        boolean ok1 = bin.delete();
        Logger.log("Status: " + ok1);
    }
}
