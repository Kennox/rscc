package ch.imedias.rsccfx.model.xml;

import ch.imedias.rsccfx.RsccApp;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.UnmarshalException;
import javax.xml.bind.Unmarshaller;

public class SupporterHelper {

  private static final Logger LOGGER =
      Logger.getLogger(SupporterHelper.class.getName());
  private static final String SUPPORT_ADDRESSES = "supportAddresses";
  private static final String DEFAULT_SUPPORTERS_FILENAME = "rscc-defaults-lernstick.xml";
  private final Preferences preferences = Preferences.userNodeForPackage(RsccApp.class);

  private List<Supporter> getSupportersFromXml(File file) {
    List<Supporter> supportersList = null;
    try {
      JAXBContext jaxbContext = JAXBContext.newInstance(Supporters.class);

      Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
      Supporters supporters = (Supporters) jaxbUnmarshaller.unmarshal(file);

      supportersList = supporters.getSupporters();
    } catch (JAXBException e) {
      e.printStackTrace();
    }
    return supportersList;
  }

  private List<Supporter> getSupportersFromXml(String string) {
    List<Supporter> supportersList = null;
    StringReader reader = new StringReader(string);

    try {
      JAXBContext jaxbContext = JAXBContext.newInstance(Supporters.class);
      Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
      Supporters supporters = (Supporters) jaxbUnmarshaller.unmarshal(reader);

      supportersList = supporters.getSupporters();
    } catch (UnmarshalException unmarshalException) {
      // gets thrown when the format is invalid, in this case return default
      supportersList = getDefaultSupporters();
    } catch (JAXBException e) {
      e.printStackTrace();
    }
    return supportersList;
  }

  private void supportersToXml(List<Supporter> supporters, File file) {
    try {
      JAXBContext jaxbContext = JAXBContext.newInstance(Supporters.class);
      Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

      Supporters supportersWrapper = new Supporters();
      supportersWrapper.setSupporters(supporters);

      jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
      jaxbMarshaller.marshal(supportersWrapper, file);
    } catch (JAXBException e) {
      e.printStackTrace();
    }
  }

  private String supportersToXml(List<Supporter> supporters) {
    String string = null;

    Supporters supportersWrapper = new Supporters();
    supportersWrapper.setSupporters(supporters);

    StringWriter writer = new StringWriter();

    try {
      JAXBContext jaxbContext = JAXBContext.newInstance(Supporters.class);
      Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

      jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
      jaxbMarshaller.marshal(supportersWrapper, writer);

      string = writer.toString();
    } catch (JAXBException e) {
      e.printStackTrace();
    }
    return string;
  }

  /**
   * Returns a default list of supporters.
   */
  public List<Supporter> getDefaultSupporters() {
    File supportersXmlFile =
        new File(getClass().getClassLoader().getResource(DEFAULT_SUPPORTERS_FILENAME).getFile());
    return getSupportersFromXml(supportersXmlFile);
  }

  /**
   * Gets the supporter list from the preferences file.
   * If no preferences are found the default list is generated.
   */
  public List<Supporter> loadSupporters() {
    // load preferences
    String supportersXml = getSupportersXmlFromPreferences();
    if (supportersXml == null) {
      // use some hardcoded defaults
      return getDefaultSupporters();
    } else {
      return getSupportersFromXml(supportersXml);
    }
  }

  public void saveSupporters(List<Supporter> supporters) {
    String supportersXml = supportersToXml(supporters);
    setSupportersInPreferences(supportersXml);
  }

  private String getSupportersXmlFromPreferences() {
    return preferences.get(SUPPORT_ADDRESSES, null);
  }

  private void setSupportersInPreferences(String supportersXmlString) {
    if (supportersXmlString != null) {
      preferences.put(SUPPORT_ADDRESSES, supportersXmlString);
    } else {
      preferences.remove(SUPPORT_ADDRESSES);
    }
  }


}

