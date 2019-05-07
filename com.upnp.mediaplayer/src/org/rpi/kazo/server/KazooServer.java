package org.rpi.kazo.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.rpi.channel.ChannelPlayList;
import org.rpi.config.Config;
import org.rpi.controlpoint.DeviceInfo;
import org.rpi.controlpoint.DeviceManager;
import org.rpi.player.PlayManager;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class KazooServer {

	private List<ChannelPlayList> channels = new ArrayList<ChannelPlayList>();
	private Logger log = Logger.getLogger(this.getClass());

	public KazooServer() {

	}

	public void getTracks(String udn, String action, String albumId) {
		
		log.debug("Get Devices: " + udn);
		channels.clear();
		DeviceInfo di = DeviceManager.getInstance().getDevice(udn);
		String host = "192.168.1.205";
		int port = 4000;
		if (di == null) {
			log.info("Device NOT Found: " + udn);
			// return;
		} else {
			host = di.getHost();
			port = di.getPort();
		}
		String url = "http://" + host + ":" + port;
		String initialUrl = url + "/ps";
		try {

			try {
				String jsonText = readFromServer(initialUrl);
				JSONObject json = new JSONObject(jsonText);

				JSONObject me = json.getJSONObject("me");
				Set<String> keys = me.keySet();
				String sMeKey = "";
				for (String t : keys) {
					sMeKey = t;
					break;
				}
				JSONObject sMeJson = me.getJSONObject(sMeKey);
				String path = sMeJson.getString("Path");
				log.debug("Paths: " + path);
				String kazooUri = url + path;
				String create = readFromServer(kazooUri + "/create");
				String sessionId = create.replaceAll("\"", "");
				log.debug(sessionId);

				// AlbumId=cmF3LmU5M2JiZTU5OTBhZTdkNmE3ZTU4ZGMwZWIyYzA3YjUx
				// ArtistId=YXJ0aXN0LjljMzZiOTkyZTBmNjUwNjA5ZjE2NjNmZWIzNTk4OGY2
				// Genre = Z2VucmUuZmY4YThkY2E1MDM1NjRmNTAyZjRmZjBmMjE5ZDRhYjI=

				// String destroy = readFromServer(kazooUri + "/destoy?session="
				// + session);
				switch (action.toUpperCase()) {
				case "ALBUM":
					addAlbum(kazooUri, sessionId, albumId, initialUrl);
					break;
				case "ARTIST":

					//Get the number of albums for this artist
					int iCount = browse(kazooUri, sessionId, albumId);
					//Browse for the albums
					String browsea = read(kazooUri, sessionId, 0, iCount);
					JSONArray jBrowsea = new JSONArray(browsea);
					//Iterate each album, get the id and then get the album.
					for (int i = 0; i < iCount; i++) {
						JSONObject jAlbum = jBrowsea.getJSONObject(i);
						String id = jAlbum.getString("Id");
						log.debug("Album ID: " + id);
						addAlbum(kazooUri, sessionId, id, initialUrl);
					}
					break;
				
				
				case "GENRE":
				{
					int iCountg = browse(kazooUri, sessionId, albumId);
					String browseg = read(kazooUri, sessionId, 0, iCountg);
					JSONArray jBrowseg = new JSONArray(browseg);
					for (int i = 0; i < iCountg; i++) {
						JSONObject jAlbum = jBrowseg.getJSONObject(i);
						String id = jAlbum.getString("Id");
						log.debug("Album ID: " + id);
						addAlbum(kazooUri, sessionId, id, initialUrl);
					}
					break;
				}
				}
				//TODO, make sure that the Playlist Max is not exceeded
				PlayManager.getInstance().podcastUpdatePlayList(channels);
				// Now we have the Path we connect
			} finally {
				// is.close();
			}
		} catch (Exception e) {
			log.error(e);
		}
	}

	/***
	 * Browse on the Kazoo API This returns the number of object for the
	 * containerId
	 * 
	 * @param path
	 * @param sessionId
	 * @param containerId
	 * @return
	 */
	private int browse(String path, String sessionId, String containerId) {
		int iCount = -1;
		try {
			String browse = readFromServer(path + "/browse?session=" + sessionId + "&id=" + containerId);
			JSONObject jBrowse = new JSONObject(browse);
			if (jBrowse.has("Total")) {
				iCount = jBrowse.getInt("Total");
			}
		} catch (Exception e) {
			log.error("Error browse: ", e);
		}
		return iCount;
	}

	private String read(String path, String sessionId, int index, int count) {
		String res = "";
		try {
			res = readFromServer(path + "/read?session=" + sessionId + "&index=" + index + "&count=" + count);
		} catch (Exception e) {
			log.error("Error Read", e);
		}
		return res;
	}

	private void addAlbum(String path, String sessionId, String albumId, String initialUrl) {
		// String list = readFromServer(kazooUri + "/read?session=" + session +
		// "&index=0&count="+iCount);
		try {
			String browse = readFromServer(path + "/browse?session=" + sessionId + "&id=" + albumId);
			JSONObject jBrowse = new JSONObject(browse);
			int iCount = jBrowse.getInt("Total");

			String list = read(path, sessionId, 0, iCount);
			JSONArray jList = new JSONArray(list);
			for (Object o : jList) {
				if (o instanceof JSONObject) {
					JSONObject track = (JSONObject) o;
					log.debug(track);
					JSONArray metaData = track.getJSONArray("Metadata");
					log.debug(metaData);
					Map<String, String> md = new HashMap<String, String>();
					for (Object om : metaData) {
						if (om instanceof JSONArray) {
							JSONArray ot = (JSONArray) om;
							String key = ot.getString(0);
							String value = ot.getString(1);
							log.debug(ot);
							String myKey = getMetaDataKey(Integer.parseInt(key));
							if (myKey != null) {
								md.put(myKey, value);
							}

						}
					}

					String title = md.get("title");
					String artworkUri = md.get("albumArtwork");
					String trackUrl = md.get("uri");
					int id = getNextTrackId();

					String m = createMetaData(title, initialUrl, artworkUri);

					ChannelPlayList channel = new ChannelPlayList(trackUrl, m, id);
					channels.add(channel);
					log.debug("Channel: " + channel);

					log.debug(md);
				}
			}
		} catch (Exception e) {
			log.error(e);
		}

	}

	private void getAlbum(String sessionId, String albumId) {

	}

	private String readFromServer(String url) throws Exception {
		InputStream is = new URL(url).openStream();
		BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
		String jsonText = readAll(rd);
		is.close();
		return jsonText;
	}

	private String readAll(Reader rd) throws IOException {
		StringBuilder sb = new StringBuilder();
		int cp;
		while ((cp = rd.read()) != -1) {
			sb.append((char) cp);
		}
		return sb.toString();
	}

	private String getMetaDataKey(int i) {
		switch (i) {
		case 101:
			return "description";
		case 102:
			return "channels";
		case 103:
			return "bitDepth";
		case 104:
			return "sampleRate";
		case 105:
			return "bitRate";
		case 106:
			return "duration";
		case 107: // codec
			return "";
		case 108:
			return "artist";
		case 109: // bpm
			return "";
		case 110:
			return "composer";
		case 111:
			return "conductor";
		case 112:
			return "disc";
		case 114:
			return "genre";
		case 115: // grouping
			return "";
		case 116: // lyrics
			return "";
		case 118:
			return "title";
		case 119:
			return "track";
		case 120:
			return "tracks";
		case 121:
			return "year";
		case 122:
			return "albumArtwork";
		case 123:
			return "uri";
		case 124: // weight
			// return "";
		case 125: // availability
			// return "";
		case 126: // favourited
			return "";
		case 201:
			return "albumTitle";
		case 202:
			return "albumArtist";
		case 203:
			return "albumArtwork";

		default: // Many more tags skipped. Its not clear whether any are
					// required
			return null;
		}
	}

	// TODO Move this somewhere more appropriate:
	private String createMetaData(String name, String url, String image) {
		String res = "";

		try {

			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			InputSource insrc = new InputSource(new StringReader(metaData));
			Document doc = builder.parse(insrc);
			Node node = doc.getFirstChild();
			Node item = node.getFirstChild();
			// int count = item.getAttributes().getLength();
			NamedNodeMap attts = item.getAttributes();
			Node nid = attts.getNamedItem("id");
			nid.setTextContent(name);
			NodeList childs = item.getChildNodes();
			for (int i = 0; i < childs.getLength(); i++) {
				Node n = childs.item(i);
				// log.debug("Name: " + n.getNodeName() + " Value " +
				// n.getTextContent());
				if (n.getNodeName() == "dc:title") {
					n.setTextContent(name);
				} else if (n.getNodeName() == "res") {
					n.setTextContent(url);
				} else if (n.getNodeName() == "upnp:albumArtURI") {
					n.setTextContent(image);
					// } else if (n.getNodeName() == "upnp:artist") {
					// n.setTextContent(name);
				} else if (n.getNodeName() == "upnp:album") {
					n.setTextContent(name);
				}
			}
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			StreamResult result = new StreamResult(new StringWriter());
			DOMSource source = new DOMSource(doc);
			transformer.transform(source, result);
			res = (result.getWriter().toString());
		} catch (Exception e) {
			// log.error("Error Creating XML Doc", e);
			e.printStackTrace();
		}
		return res;
	}

	private int getNextTrackId() {
		int res = 0;
		int max = Config.getInstance().getMediaplayerPlaylistMax() * 2;
		for (int i = 1; i < max; i++) {
			if (!duplicateTrackId(i) && !PlayManager.getInstance().duplicateTrackId(i)) {
				return i;
			}
		}
		return res;
	}

	public boolean duplicateTrackId(int id) {
		for (ChannelPlayList ch : channels) {
			if (ch.getId() == id) {
				return true;
			}
		}
		return false;
	}

	private String metaData = "<DIDL-Lite xmlns='urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/'><item id=''><dc:title xmlns:dc='http://purl.org/dc/elements/1.1/'></dc:title><upnp:artist role='Performer' xmlns:upnp='urn:schemas-upnp-org:metadata-1-0/upnp/'></upnp:artist><upnp:album xmlns:upnp='urn:schemas-upnp-org:metadata-1-0/upnp/'/><upnp:class xmlns:upnp='urn:schemas-upnp-org:metadata-1-0/upnp/'>object.item.audioItem</upnp:class><res bitrate='' nrAudioChannels='' protocolInfo='http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01'></res><upnp:albumArtURI xmlns:upnp='urn:schemas-upnp-org:metadata-1-0/upnp/'></upnp:albumArtURI></item></DIDL-Lite>";

}
