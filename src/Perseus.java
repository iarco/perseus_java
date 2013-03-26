import java.io.*;
import java.net.*;
import java.util.*;

public class Perseus {
    public static void main(String[] args) {
        PerseusServer server = new PerseusServer();
        server.Start();
    }
}

class HttpCommonVersion {
    public static final String HTTP10 = "HTTP/1.0";
    public static final String HTTP11 = "HTTP/1.1";
}

class HttpCommonHeaders {
    public HashMap<String, String> headers = new HashMap();
    
    public HttpCommonHeaders(String request) {
        if(request.trim().length() > 0) {
            String[] splitRequest = request.split("\r\n");
            
            if(splitRequest.length > 0) {
                for (int i = 0; i < splitRequest.length; i++) {
                    String[] pair = splitRequest[i].split(":", 2);
                    this.headers.put(pair[0], pair.length > 1 ? pair[1].trim() : new String());
                }
            }
        }
    }
    
    @Override
    public String toString() {
        String returnValue = new String();
        
        Iterator<String> i = this.headers.keySet().iterator();
        while(i.hasNext()) {
            String key = i.next();
            returnValue += String.format("%s: %s\r\n", key, this.headers.get(key));
        }
        
        return returnValue;
    }
}

class HttpCommonContentTypes {
    public static final HashMap<String, String> contentTypes = new HashMap();
    
    static {
        contentTypes.put("htm",     "text/html");
        contentTypes.put("html",    "text/html");
        
        contentTypes.put("txt",     "text/plain");
        
        contentTypes.put("gif",     "image/gif");
        contentTypes.put("png",     "image/png");
        contentTypes.put("jpg",     "image/jpeg");
        contentTypes.put("jpeg",    "image/jpeg");
    }
}

enum HttpRequestMethod {
    Get,
    Unsupported
}

class HttpRequestURL {
    public String path = "/";
    public HashMap<String, String> query = new HashMap();
    
    public HttpRequestURL(String url) {
        // /folder/file?param1=value1&param2=value2&param3
        
        if(url.length() > 0) {
            String rawQuery = new String();
            
            String[] split1 = url.split("\\?", 2);
            this.path = split1[0];
            if (split1.length > 1) { rawQuery = split1[1]; }
            
            String[] pairs = rawQuery.split("&");
            if(pairs.length > 0) {
                for(int i = 0; i < pairs.length; i++) {
                    String[] pair = pairs[i].split("=", 2);
                    this.query.put(pair[0], pair.length > 1 ? pair[1] : new String());
                }
             }
        }
    }
}

class HttpRequest {
    
    public HttpRequestMethod method = HttpRequestMethod.Unsupported;
    public HttpRequestURL URL = null; 
    public String httpVersion = new String();
    
    private String _method;
    private String _URL;
    private String _httpVersion;
    
    public HttpRequest(String requestText) {
        // GET / HTTP/1.1
        
        String[] splitRequest = requestText.split(" ", 3);
        
        _method = splitRequest[0];

        if(_method.toLowerCase().equals("get")) {
            this.method = HttpRequestMethod.Get;
        } else {
            this.method = HttpRequestMethod.Unsupported;
        }
        
        if(splitRequest.length > 1) {
            _URL = splitRequest[1];
            this.URL = new HttpRequestURL(_URL);
        }
        
        if(splitRequest.length > 2) {
            _httpVersion = splitRequest[2];
            
            this.httpVersion = HttpCommonVersion.HTTP10;
            if(_httpVersion.equals(HttpCommonVersion.HTTP11)) { this.httpVersion = HttpCommonVersion.HTTP11; }
            
        }
    }
}

class HttpResponseStatusCodes {
    public static final String HTTP200OK = "200 OK";
    public static final String HTTP302Redirect = "302 Found";
    public static final String HTTP400BadRequest = "400 Bad Request";
    public static final String HTTP404NotFound = "404 Not Found";
    public static final String HTTP500InternalServerError = "500 Internal Server Error";
    
    public static String CreateStatusLine(String httpVersion, String statusCode) {
        return httpVersion + " " + statusCode;
    }
}

class PerseusHttpRequest {
    public HttpRequest request = null;
    public HttpCommonHeaders headers = null;
    
    public PerseusHttpRequest(String requestText) {
        String[] splitRequest = requestText.split("\r\n", 2);
        
        this.request = new HttpRequest(splitRequest[0]);
        this.headers = splitRequest.length > 1 ? new HttpCommonHeaders(splitRequest[1]) : new HttpCommonHeaders(new String());
    }
}

class PerseusHttpResponse {
    String statusLine;
    HttpCommonHeaders headers;
    byte[] data;
    
    public PerseusHttpResponse(String statusLine, HttpCommonHeaders headers, byte[] data) {
        this.statusLine = statusLine;
        this.headers = headers;
        this.data = data.clone();
    }
    
    @Override
    public String toString() {
        return String.format("%s\r\n%s\r\n%s bytes", this.statusLine, this.headers.toString(), Integer.toString(this.data.length));
    }
    
    public byte[] toBytes() {
        byte[] byteStatusLine = (this.statusLine + "\r\n").getBytes();
        byte[] byteHeaders = (this.headers.toString() + "\r\n").getBytes();
        return ConcatenateBytes(byteStatusLine, byteHeaders, this.data);
    }
    
    private byte[] ConcatenateBytes(byte[] first, byte[]... rest) {
        int totalLength = first.length;
        
        for(byte[] array : rest) {
            totalLength += array.length;
        }
        
        byte[] returnValue = Arrays.copyOf(first, totalLength);
        int offset = first.length;
        
        for(byte[] array : rest) {
            System.arraycopy(array, 0, returnValue, offset, array.length);
            offset += array.length;
        }
        
        return returnValue;
    }
}

class PerseusWebThread extends Thread {
    
    private Socket clientSocket = null;
    private String name = new String();
    
    public PerseusWebThread(Socket sock) {
        this.clientSocket = sock;
        this.name = "WWW / " + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
    }
    
    @Override
    public void run() {
        try {
            DataInputStream d = new DataInputStream(clientSocket.getInputStream());
            byte[] readBuffer = new byte[8192];
            int readCount = d.read(readBuffer, 0, 8192);
            
            // -1 - Конец потока
            // 0 - Ничего не прочитали
            
            if(readCount > 0) {
                // Собираем строку
                String requestString = new String(readBuffer, 0, readCount);
                PerseusHttpRequest request = new PerseusHttpRequest(requestString);
                PerseusHttpResponse response = null;
                
                switch(request.request.method) {
                    case Get: response = CreateGetResponse(request); break;
                    case Unsupported: response = CreateUnsupportedResponse(request); break;
                }
                    
                response.headers.headers.put("Server", "Perseus");
                
                SendToClient(request, response);
                
            }
        }
        catch (Exception e) {
            
        }
        
        try { clientSocket.close(); }
        catch (Exception e) {}
    }
    
    private PerseusHttpResponse CreateGetResponse(PerseusHttpRequest request) {
        String statusLine = new String();
        HttpCommonHeaders headers = new HttpCommonHeaders(new String());
        byte[] content = new byte[0];
        
        // Здесь у нас разделитель всегда /
        
        String preparedPath = request.request.URL.path.replace("/", File.separator);
        File currentDirectory = new File(".");
        String completePath = new String();
        boolean fileError = false;
        
        try {
            completePath = currentDirectory.getCanonicalPath() + preparedPath;
        }
        catch (Exception e) {
            fileError = true;
        }
        
        if(fileError) {
            statusLine = HttpResponseStatusCodes.CreateStatusLine(request.request.httpVersion, HttpResponseStatusCodes.HTTP500InternalServerError);
        } else {
            File requestedFile = new File(completePath);
            boolean directoryRequested = request.request.URL.path.endsWith("/");
            
            if(requestedFile.exists()) {
                // Файл или папка существует
                
                if(requestedFile.isDirectory()) {
                    // То, что существует, папка
                    
                    if(directoryRequested) {
                        // Вернем список файлов в папке
                        statusLine = HttpResponseStatusCodes.CreateStatusLine(
                                request.request.httpVersion,
                                HttpResponseStatusCodes.HTTP200OK);
                        
                        content = CreateGetResponseDirectory(requestedFile, headers);
                        
                    } else {
                        // Редирект на папку (запрос был на файл)
                        statusLine = HttpResponseStatusCodes.CreateStatusLine(
                                request.request.httpVersion,
                                HttpResponseStatusCodes.HTTP302Redirect);
                        
                        headers.headers.put("Location", request.request.URL.path + "/");
                    }
                } else {
                    // То, что существует, файл
                    
                    if(!directoryRequested) {
                        // Вернем запрошенный файл
                        
                        statusLine = HttpResponseStatusCodes.CreateStatusLine(
                                request.request.httpVersion,
                                HttpResponseStatusCodes.HTTP200OK);
                        
                        content = CreateGetResponseFile(requestedFile, headers);                        
                        
                    } else {
                        // Вернем 404
                        statusLine = HttpResponseStatusCodes.CreateStatusLine(
                                request.request.httpVersion,
                                HttpResponseStatusCodes.HTTP404NotFound);
                    }
                }
            } else {
                // Вернем 404
                
                statusLine = HttpResponseStatusCodes.CreateStatusLine(request.request.httpVersion, HttpResponseStatusCodes.HTTP404NotFound);
            }
        }
        
        return new PerseusHttpResponse(statusLine, headers, content);
    }
    
    private byte[] CreateGetResponseFile(File file, HttpCommonHeaders headers) {
        
        FileInputStream f = null;
        try {
            f = new FileInputStream(file);
        } catch (FileNotFoundException ex) {
            // TODO
        }
        
        DataInputStream d = new DataInputStream(f);
        byte[] b = new byte[(int)file.length()];
        
        try {
            d.readFully(b);
        } catch (IOException ex2) {
            // TODO
        }
        
        String fileName = file.getName();
        int indexOfDot = fileName.indexOf(".");
        if(indexOfDot > -1) {
            // В имени файла есть хотя бы одна точка
           String extension = fileName.substring(indexOfDot + 1);
           headers.headers.put("Content-Type",
                   HttpCommonContentTypes.contentTypes.get(extension));
        } else {
           headers.headers.put("Content-Type",
                   HttpCommonContentTypes.contentTypes.get("html"));            
        }
        
        headers.headers.put("Content-Lenght",
                Integer.toBinaryString(b.length));
        
        return b;
    }
    
    private byte[] CreateGetResponseDirectory(File directory, HttpCommonHeaders headers) {
        
        String returnValue = new String();
        
        String returnHTMLTemplate =
                "<html><head><title>%s</title></head>" + 
                "<body><h1>%s</h1>%s</body></html>";
        
        String itemTemplate = "<a href=\"%s\">%s</a><br />\r\n";
        
        String items = new String();
        
        File[] files = directory.listFiles();
        if (files.length > 0) {
            
            for(File file : files) {
                if (file.isDirectory()) {
                    // Папка
                    items += String.format(
                            itemTemplate,
                            file.getName() + "/",
                            file.getName() + "/");
                } else {
                    // Файл
                    items += String.format(
                            itemTemplate,
                            file.getName(),
                            file.getName());                    
                }
            }            
        } else {
            items = "Empty folder";
        }
        
        returnValue = String.format(returnHTMLTemplate,
                directory.getName(),
                directory.getName(),
                items);
        
        headers.headers.put("Content-Type",
                HttpCommonContentTypes.contentTypes.get("html"));
        
        return returnValue.getBytes();
    }
    
    private PerseusHttpResponse CreateUnsupportedResponse(PerseusHttpRequest request) {
        String statusLine = HttpResponseStatusCodes.CreateStatusLine(
                request.request.httpVersion,
                HttpResponseStatusCodes.HTTP400BadRequest);
        
        HttpCommonHeaders headers = new HttpCommonHeaders(new String());
        
        return new PerseusHttpResponse(statusLine, headers, new byte[0]);
    }
    
    private void SendToClient(PerseusHttpRequest request, PerseusHttpResponse response) {
        byte[] byteData = response.toBytes();
        
        try {
            if (clientSocket.isConnected()) {
                DataOutputStream d =
                        new DataOutputStream(clientSocket.getOutputStream());
                
                d.write(byteData);
                
                if(d.size() != byteData.length) {
                    // TODO: Произошка ошибка отправки
                } else {
                    // Данные успешно отправлены
                    System.out.printf("%s: Sent %d bytes, %s [%s]\r\n",
                            this.name,
                            byteData.length,
                            response.statusLine,
                            request.request.URL.path);
                }
            } else {
                // TODO: Клиент отвалился
            }
        } catch (Exception e) {
            // TODO: Ошибка сокета
        }
    }
}

class PerseusServer {
    private static final int WEB_PORT = 8090;
    private static final int QUEUE_LENGTH = 100;
    private static final int TIMEOUT_RESTART = 60;
    private long lastRestart = 0;
    
    public void Start() {
        ServerSocket webSocket = null;
        
        while(true) {
            try {
                Socket acceptedSocket = webSocket.accept();
                
                new PerseusWebThread(acceptedSocket).start();
            }
            catch (Exception se) {
                long diff = (System.currentTimeMillis() - lastRestart) / 1000;
                
                if(diff > TIMEOUT_RESTART) {
                    lastRestart = System.currentTimeMillis();
                    
                    if (webSocket != null) {
                        try { webSocket.close(); }
                        catch (Exception closeException) {
                            // TODO: сообщить пользователю
                        }
                    }
                    
                    try { webSocket = new ServerSocket(WEB_PORT, QUEUE_LENGTH); }
                    catch (Exception createException) {
                        // TODO: сообщить пользователю
                    }
                }
            }
        }
    }
}