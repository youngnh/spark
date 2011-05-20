package sherpa.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

import org.apache.avro.Protocol;

public class SherpaProtocol {
  public static final Protocol PROTOCOL;

  static {
    
    try {
      InputStream schemaStream = SherpaProtocol.class.getResourceAsStream("/sherpa/protocol/sherpa.js");
      Reader schemaReader = new InputStreamReader(schemaStream);
      BufferedReader bufferedReader = new BufferedReader(schemaReader);
      StringBuilder schema = new StringBuilder();
      while(true) {
        String line = bufferedReader.readLine();
        if(line != null) {
          schema.append(line);  
        } else {
          break;
        }        
      }
      PROTOCOL = Protocol.parse(schema.toString());
    } catch (IOException e) {
      throw new RuntimeException(
          "Unable to parse SHERPA protocol definition file", e);
    }
  }
}
