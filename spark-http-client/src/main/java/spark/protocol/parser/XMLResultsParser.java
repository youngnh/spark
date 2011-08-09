/*
 * Copyright 2010 Paul Gearon.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package spark.protocol.parser;

import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;
import static spark.protocol.parser.XMLResultsParser.Element.HEAD;
import static spark.protocol.parser.XMLResultsParser.Element.LINK;
import static spark.protocol.parser.XMLResultsParser.Element.RESULTS;
import static spark.protocol.parser.XMLResultsParser.Element.SPARQL;
import static spark.protocol.parser.XMLResultsParser.Element.VARIABLE;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spark.api.Command;
import spark.api.Result;
import spark.api.exception.SparqlException;
import spark.protocol.ProtocolCommand.ResultType;
import spark.protocol.ProtocolDataSource;

/**
 * This class starts the parsing of SPARQL XML results to determine what kind of
 * results they are, and then instantiates the correct result type.
 * 
 * Based on jSPARQLc, Copyright 2010 Paul Gearon.
 * http://code.google.com/p/jsparqlc/
 * jSPARQLc is licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * @author Paul Gearon
 */
public final class XMLResultsParser implements ResultParser {

  private static final Logger logger = LoggerFactory.getLogger(XMLResultsParser.class);
  
  /** Enumeration of the elements found in a SPARQL result document. */
  public enum Element { SPARQL, HEAD, RESULTS, VARIABLE, LINK, RESULT, BINDING, URI, LITERAL, BNODE, BOOLEAN };

  /** Namespace for the xml:base attribute */
  private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
  
  /** Local name for the xml:base attribute */
  private static final String BASE = "base";
  
  /** The reference attribute used for metadata */
  private static final String HREF = "href";

  /** Parses the input stream as either XML select or ask results. */
  @Override
  public Result parse(Command cmd, InputStream input, ResultType type) {
    return createResults(cmd, input, type);
  }

  /**
   * Constructs an XMLResults object based on the contents of the given stream.
   * @param stream The input stream containing raw XML.
   * @param query The query used to generate the stream.
   * @return A new XMLResults object. Either variable bindings, or a boolean result.
   * @throws SparqlException If the data stream was not valid.
   */
  public static Result createResults(Command cmd, InputStream stream, ResultType type) throws SparqlException {
    XMLInputFactory xmlStreamFactory = XMLInputFactory.newInstance();
    xmlStreamFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
    XMLStreamReader rdr;
    try {
      rdr = xmlStreamFactory.createXMLStreamReader(stream, "UTF-8");
    } catch (XMLStreamException e) {
      throw new SparqlException("Unable to open XML data", e);
    }
    List<String> cols = new ArrayList<String>();
    List<String> md = new ArrayList<String>();

    try {
      if (rdr.nextTag() != START_ELEMENT || !nameIs(rdr, SPARQL)) {
        throw new SparqlException("Result is not a SPARQL XML result document");
      }
      
      // Initialize the base URI to the 
      String base = null;
      if (cmd != null) {
        base = ((ProtocolDataSource)cmd.getConnection().getDataSource()).getUrl().toString();
      }
      
      // read the header information
      parseHeader(base, rdr, cols, md);
      
      // move the cursor into the results, and read in the first row
      if (rdr.nextTag() != START_ELEMENT) throw new SparqlException("No body to result document");
    } catch (XMLStreamException e) {
      throw new SparqlException("Error reading the XML stream", e);
    }
    String typeName = rdr.getLocalName();
    if (typeName.equalsIgnoreCase(RESULTS.toString())) {
      if (type != null && type != ResultType.SELECT) {
        throw new SparqlException("Unexpected result type; expected " + type + " but found SELECT.");
      }
      return new XMLSelectResults(cmd, rdr, cols, md);
    }
    //if (typeName.equalsIgnoreCase(BOOLEAN.toString())) return new XMLAskResult(rdr, query, md);

    throw new SparqlException("Unknown element type in result document. Expected <results> or <boolean> but got <" + typeName + ">");
  }

  /**
   * Parse the &lt;head&gt; element with the variables and metadata.
   * @param base The base URI, initialized to the endpoint URL if known.
   * @param rdr The XML reader to parse information from.
   * @param cols A list to populate with the columns that may appear in the header.
   * @param md A list to populate with metada that may appear in the header.
   * @throws XMLStreamException There was an error reading the XML stream.
   * @throws SparqlException The XML was not valid SPARQL results.
   */
  static private void parseHeader(String base, XMLStreamReader rdr, List<String> cols, List<String> md) throws XMLStreamException, SparqlException {
    logger.debug("xml:base is initially {}", base);
    base = getBase(base, rdr);
    testOpen(rdr, rdr.nextTag(), HEAD, "Missing header from XML results");
    base = getBase(base, rdr);
    boolean endedVars = false;
    int eventType;
    while ((eventType = rdr.nextTag()) != END_ELEMENT || !nameIs(rdr, HEAD)) {
      if (eventType == START_ELEMENT) {
        if (nameIs(rdr, VARIABLE)) {
          if (endedVars) throw new SparqlException("Encountered a variable after header metadata");
          String var = rdr.getAttributeValue(null, "name");
          if (var != null) cols.add(var);
          else logger.warn("<variable> element without 'name' attribute");
        } else if (nameIs(rdr, LINK)) {
          String b = getBase(base, rdr); // Copy to a new var since we're looping.
          String href = rdr.getAttributeValue(null, HREF);
          if (href != null) md.add(resolve(b, href));
          else logger.warn("<link> element without 'href' attribute");
          endedVars = true;
        }
      }
    }
    // ending on </head>. next() should be <results> or <boolean>
    testClose(rdr, eventType, HEAD, "Unexpected element in header: " + rdr.getLocalName());
  }

  /** Returns the new base URI, based on the old base URI and the xml:base value in the current element. */
  static private String getBase(String oldBase, XMLStreamReader rdr) {
    String newBase =  resolve(oldBase, rdr.getAttributeValue(XML_NS, BASE));
    if (newBase != oldBase) {
      logger.debug("xml:base is now {}", newBase);
    }
    return newBase;
  }
  
  /**
   * Attempts to resolve the given href against the given base, as a URI. Works similarly to
   * URI.resolve(), but attempts to handle syntax exceptions in a more graceful manner.
   */
  static private String resolve(String base, String rel) {
    // Short-circuit check for unspecified base or relative URI.
    if (base == null || base.isEmpty()) return rel;
    if (rel == null || rel.isEmpty()) return base;
    
    // Attempt to parse both strings as URIs.
    URI b = null;
    try {
      b = new URI(base);
    } catch (URISyntaxException IGNORE) {
      // ignore, we'll handle this later.
    }
    URI r = null;
    try {
      r = new URI(rel);
    } catch (URISyntaxException IGNORE) {
      // ignore, we'll handle this later.
    }
    
    // Both URIs parsed OK, proceed as usual.
    if (b != null && r != null) return b.resolve(r).toString();
    
    // Syntax exception in the base or relative URI, need to fudge here...
    // Is the URI being resolved absolute (or does it look like it)?
    if ((r != null && r.isAbsolute()) || rel.startsWith("http:")) return rel;
    
    // One or both of the strings didn't parse as a valid URI so URI.resolve won't help us here.
    // Just smash them together and hope for the best.
    return base + rel;
  }
  
  /**
   * Convenience method to test if the current local name is the same as an expected element.
   * @param rdr The XMLStreamReader to get the current state from.
   * @param elt The element to test against.
   * @return <code>true</code> iff the current local name is the same as the element name.
   */
  static final boolean nameIs(XMLStreamReader rdr, Element elt) {
    return rdr.getLocalName().equalsIgnoreCase(elt.name());
  }

  /**
   * Convenience method to test if the read element is an opening tag with the correct name.
   * @param rdr The XMLStreamReader to get the current state from.
   * @param type The kind of element to test.
   * @param elt The element name.
   * @param message The message to use in case of error.
   * @throws SparqlException Thrown if the type is not a START_ELEMENT or the name is not the required name.
   */
  static final void testOpen(XMLStreamReader rdr, int type, Element elt, String message) throws SparqlException {
    if (type != START_ELEMENT || !nameIs(rdr, elt)) throw new SparqlException(message);
  }

  /**
   * Convenience method to test if the read element is a closing tag with the correct name.
   * @param rdr The XMLStreamReader to get the current state from.
   * @param type The kind of element to test.
   * @param elt The element name.
   * @param message The message to use in case of error.
   * @throws SparqlException Thrown if the type is not an END_ELEMENT or the name is not the required name.
   */
  static final void testClose(XMLStreamReader rdr, int type, Element elt, String message) throws SparqlException {
    if (type != END_ELEMENT || !nameIs(rdr, elt)) throw new SparqlException(message);
  }

}
