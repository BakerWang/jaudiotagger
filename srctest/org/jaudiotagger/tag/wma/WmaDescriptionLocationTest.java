package org.jaudiotagger.tag.wma;

import org.jaudiotagger.audio.asf.io.AsfStreamer;

import org.jaudiotagger.audio.asf.io.AsfExtHeaderModifier;

import org.jaudiotagger.audio.asf.io.ChunkModifier;

import org.jaudiotagger.audio.asf.data.ExtendedContentDescription;

import org.jaudiotagger.audio.asf.io.WriteableChunkModifer;
import org.jaudiotagger.audio.asf.util.TagConverter;

import org.jaudiotagger.audio.asf.data.AsfHeader;

import org.jaudiotagger.audio.asf.io.AsfHeaderReader;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.asf.tag.AsfFieldKey;
import org.jaudiotagger.audio.asf.tag.AsfTag;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * This testcase tests the ability to read the content description and extended content description 
 * from the ASF header object and the ASF header extension object.
 * 
 * @author Christian Laireiter
 */
public class WmaDescriptionLocationTest extends WmaTestCase
{

    /**
     * Test file to use as source.
     */
    public final static String TEST_FILE = "test1.wma"; //$NON-NLS-1$

    /**
     * Will hold a tag instance for writing some values.
     */
    private final AsfTag testTag;

    /**
     * Creates an instance.
     */
    public WmaDescriptionLocationTest()
    {
        super(TEST_FILE);
        this.testTag = new AsfTag(true);
        this.testTag.setArtist("TheArtist");
        this.testTag.set(AsfTag.createTextField(AsfFieldKey.ISVBR.getPublicFieldId(), Boolean.TRUE.toString()));
    }


    /**
     * Applies {@link #testTag} to the given audio file, and allows to specify at which location the
     * content description and extended content description are to be added.<br>
     * @param testFile The file to work with.
     * @param hcd <code>true</code> if the content description should be placed into the header object. if <code>false</code>
     *            it will be placed in the header extension object.
     * @param hecd <code>true</code> if the extended content description should be placed into the header object. if <code>false</code>
     *            it will be placed in the header extension object.
     * @throws Exception on I/O Errors.
     */
    private void applyTag(File testFile, boolean hcd, boolean hecd) throws Exception
    {
        // get an audio file instance
        AudioFile read = AudioFileIO.read(testFile);
        // delete all managed data 
        AudioFileIO.delete(read);
        // create creator for the content description object (chunk)
        WriteableChunkModifer cdCreator = new WriteableChunkModifer(TagConverter.createContentDescription(this.testTag));
        ExtendedContentDescription ecd = new ExtendedContentDescription();
        TagConverter.assignCommonTagValues(testTag, ecd);
        TagConverter.assignOptionalTagValues(testTag, ecd);
        // create creator for the extended content description object (chunk) 
        WriteableChunkModifer ecdCreator = new WriteableChunkModifer(ecd);
        // create the modifier lists
        List<ChunkModifier> headerMods = new ArrayList<ChunkModifier>();
        List<ChunkModifier> extHeaderMods = new ArrayList<ChunkModifier>();
        if (hcd)
        {
            headerMods.add(cdCreator);
        }
        else
        {
            extHeaderMods.add(cdCreator);
        }
        if (hecd)
        {
            headerMods.add(ecdCreator);
        }
        else
        {
            extHeaderMods.add(ecdCreator);
        }
        headerMods.add(new AsfExtHeaderModifier(extHeaderMods));
        File destination = prepareTestFile("chunkloc.wma");
        new AsfStreamer()
                        .createModifiedCopy(new FileInputStream(testFile), new FileOutputStream(destination), headerMods);
        checkExcpectations(destination, hcd, hecd, !hcd, !hecd);

    }

    /**
     * Tests whether the audio file contains artist and variable bitrate as specified in the
     * {@linkplain #WmaDescriptionLocationTest() constructor}, and if a content description object as well
     * as an extended content description is available.
     * @param testFile file to test
     * @param hcd <code>true</code> if a content description is expected in the ASF header.
     * @param hecd <code>true</code> if an extended content description is expected in the ASF header.
     * @param ehcd <code>true</code> if a content description is expected in the ASF header extension.
     * @param ehecd <code>true</code> if an extended content description is expected in the ASF header extension.
     * @throws Exception on I/O Errors
     */
    private void checkExcpectations(File testFile, boolean hcd, boolean hecd, boolean ehcd, boolean ehecd) throws Exception
    {
        AudioFile read = AudioFileIO.read(testFile);
        assertTrue(read.getAudioHeader().isVariableBitRate());
        assertEquals("TheArtist", read.getTag().getFirstArtist());
        AsfHeader readHeader = AsfHeaderReader.readHeader(testFile);
        assertNotNull(readHeader.findContentDescription());
        assertNotNull(readHeader.findExtendedContentDescription());
        assertEquals(hcd, readHeader.getContentDescription() != null);
        assertEquals(hecd, readHeader.getExtendedContentDescription() != null);
        assertEquals(ehcd, readHeader.getExtendedHeader() != null && readHeader.getExtendedHeader()
                        .getContentDescription() != null);
        assertEquals(ehecd, readHeader.getExtendedHeader() != null && readHeader.getExtendedHeader()
                        .getExtendedContentDescription() != null);
    }

    /**
     * Tests the locations of the content descriptor object and the extended content descriptor object, upon
     * some deep ASF manipulations.
     * 
     * @throws Exception On I/O Errors
     */
    public void testChunkLocations() throws Exception
    {
        File testFile = prepareTestFile(null);
        AudioFile read = AudioFileIO.read(testFile);
        AudioFileIO.delete(read);
        read.setTag(testTag);
        read.commit();
        checkExcpectations(testFile, true, true, false, false);
        applyTag(testFile, false, false);
        applyTag(testFile, false, true);
        applyTag(testFile, true, false);
        applyTag(testFile, true, true);
    }

}