/*
 * Entagged Audio Tag library
 * Copyright (c) 2003-2005 Raphaël Slinckx <raphael@slinckx.net>
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *  
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.jaudiotagger.audio.mp4;

import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.CannotWriteException;
import org.jaudiotagger.audio.mp4.atom.*;
import org.jaudiotagger.logging.ErrorMessage;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagOptionSingleton;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.mp4.Mp4TagCreator;
import org.jaudiotagger.utils.tree.DefaultMutableTreeNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.logging.Logger;


/**
 * Writes metadata from mp4, the metadata tags are held under the ilst atom as shown below, (note all free atoms are
 * optional).
 * <p/>
 * <p/>
 * When writing changes the size of all the atoms upto ilst has to be recalculated, then if the size of
 * the metadata is increased the size of the free atom (below meta) should be reduced accordingly or vice versa.
 * If the size of the metadata has increased by more than the size of the free atom then the size of meta, udta
 * and moov should be recalculated and the top level free atom reduced accordingly
 * If there is not enough space even if using both of the free atoms, then the mdat atom has to be shifted down
 * accordingly to make space, and the stco atom has to have its offsets to mdat chunks table adjusted accordingly.
 * <p/>
 * Exceptions are that the meta/udta/ilst do not currently exist, in which udta/meta/ilst are created. Note it is valid
 * to have meta/ilst without udta but this is less common so we always try to write files according to the Apple/iTunes
 * specification. *
 * <p/>
 * <p/>
 * <pre>
 * |--- ftyp
 * |--- free
 * |--- moov
 * |......|
 * |......|----- mvdh
 * |......|----- trak
 * |......|----- udta
 * |..............|
 * |..............|-- meta
 * |....................|
 * |....................|-- hdlr
 * |....................|-- ilst
 * |....................|.. ..|
 * |....................|.....|---- @nam (Optional for each metadatafield)
 * |....................|.....|.......|-- data
 * |....................|.....|....... ecetera
 * |....................|.....|---- ---- (Optional for reverse dns field)
 * |....................|.............|-- mean
 * |....................|.............|-- name
 * |....................|.............|-- data
 * |....................|................ ecetere
 * |....................|-- free
 * |--- free
 * |--- mdat
 * </pre>
 */
public class Mp4TagWriter
{
    // Logger Object
    public static Logger logger = Logger.getLogger("org.jaudiotagger.tag.mp4");

    private Mp4TagCreator tc = new Mp4TagCreator();


    /**
     * Replace the ilst metadata
     * <p/>
     * Because it is the same size as the original data nothing else has to be modified
     *
     * @param fileReadChannel
     * @param fileWriteChannel
     * @param newIlstData
     * @throws CannotWriteException
     * @throws IOException
     */
    private void writeMetadataSameSize(FileChannel fileReadChannel, FileChannel fileWriteChannel, Mp4BoxHeader ilstHeader, ByteBuffer newIlstData, Mp4BoxHeader tagsHeader) throws CannotWriteException, IOException
    {
        logger.config("Writing:Option 1:Same Size");

        fileReadChannel.position(0);
        fileWriteChannel.transferFrom(fileReadChannel, 0, ilstHeader.getFilePos());
        fileWriteChannel.position(ilstHeader.getFilePos());
        fileWriteChannel.write(newIlstData);
        fileReadChannel.position(ilstHeader.getFilePos() + ilstHeader.getLength());

        writeDataAfterIlst(fileReadChannel, fileWriteChannel, tagsHeader);
    }

    /**
     * If the existing files contains a tags atom and chp1 atom underneath the meta atom that means the file was
     * encoded by Nero. Applications such as foobar read this non-standard tag before the more usual data within
     * ilst causing problems. So the solution is to convert the tags atom and its children into a free atom whilst
     * leaving the chp1 atom alone.
     *
     * @param fileReadChannel
     * @param fileWriteChannel
     * @param tagsHeader
     * @throws IOException
     */
    private void writeNeroData(FileChannel fileReadChannel, FileChannel fileWriteChannel, Mp4BoxHeader tagsHeader) throws IOException, CannotWriteException
    {
        //Write from after ilst upto tags atom
        long writeBetweenIlstAndTags = tagsHeader.getFilePos() - fileReadChannel.position();
        fileWriteChannel.transferFrom(fileReadChannel, fileWriteChannel.position(), writeBetweenIlstAndTags);
        fileWriteChannel.position(fileWriteChannel.position() + writeBetweenIlstAndTags);

        //Replace tags atom (and children) by a free atom
        convertandWriteTagsAtomToFreeAtom(fileWriteChannel, tagsHeader);

        //Write after tags atom
        fileReadChannel.position(tagsHeader.getFilePos() + tagsHeader.getLength());
        writeDataInChunks(fileReadChannel, fileWriteChannel);
    }

    /**
     * When the size of the metadata has changed and it cant be compensated for by free atom
     * we have to adjust the size of the size field upto the moovheader level for the udta atom and
     * its child meta atom.
     *
     * @param moovHeader
     * @param moovBuffer
     * @param sizeAdjustment can be negative or positive     *
     * @param udtaHeader
     * @param metaHeader
     * @return
     * @throws java.io.IOException
     */
    private void adjustSizeOfMoovHeader(Mp4BoxHeader moovHeader, ByteBuffer moovBuffer, int sizeAdjustment, Mp4BoxHeader udtaHeader, Mp4BoxHeader metaHeader) throws IOException
    {
        //Adjust moov header size, adjusts the underlying buffer
        moovHeader.setLength(moovHeader.getLength() + sizeAdjustment);

        //Edit the fields in moovBuffer (note moovbuffer doesnt include header)
        if (udtaHeader != null)
        {
            //Write the updated udta atom header to moov buffer
            udtaHeader.setLength(udtaHeader.getLength() + sizeAdjustment);
            moovBuffer.position((int) (udtaHeader.getFilePos() - moovHeader.getFilePos() - Mp4BoxHeader.HEADER_LENGTH));
            moovBuffer.put(udtaHeader.getHeaderData());
        }

        if (metaHeader != null)
        {
            //Write the updated udta atom header to moov buffer
            metaHeader.setLength(metaHeader.getLength() + sizeAdjustment);
            moovBuffer.position((int) (metaHeader.getFilePos() - moovHeader.getFilePos() - Mp4BoxHeader.HEADER_LENGTH));
            moovBuffer.put(metaHeader.getHeaderData());
        }
    }


    private void createMetadataAtoms(Mp4BoxHeader moovHeader, ByteBuffer moovBuffer, int sizeAdjustment, Mp4BoxHeader udtaHeader, Mp4BoxHeader metaHeader) throws IOException
    {
        //Adjust moov header size
        moovHeader.setLength(moovHeader.getLength() + sizeAdjustment);

    }

    /**
     * Existing metadata larger than new metadata so we can just add a free atom
     *
     * @param fileReadChannel
     * @param fileWriteChannel
     * @param sizeOfExistingMetaLevelFreeAtom
     * @param neroTagsHeader
     * @param relativeIlstposition
     * @param newIlstData
     * @param moovHeader
     * @param moovBuffer
     * @param mdatHeader
     * @param stco
     * @param udtaHeader
     * @param metaHeader
     * @param ilstHeader
     * @throws IOException
     * @throws CannotWriteException
     */
    private void writeOldMetadataLargerThanNewMetadata(FileChannel fileReadChannel, FileChannel fileWriteChannel, int sizeOfExistingMetaLevelFreeAtom, Mp4BoxHeader neroTagsHeader, int relativeIlstposition, ByteBuffer newIlstData, Mp4BoxHeader moovHeader, ByteBuffer moovBuffer, Mp4BoxHeader mdatHeader, Mp4StcoBox stco, Mp4BoxHeader udtaHeader, Mp4BoxHeader metaHeader, Mp4BoxHeader ilstHeader) throws IOException, CannotWriteException
    {
        logger.config("Writing:Option 1:Smaller Size");

        //Create an amended freeBaos atom and write it if it previously existed as a free atom immediately
        //after ilst as a child of meta

        int sizeRequiredByNewIlstAtom = newIlstData.limit();

        if (sizeOfExistingMetaLevelFreeAtom > 0)
        {
            logger.config("Writing:Option 2:Smaller Size have free atom:" + ilstHeader.getLength() + ":" + sizeRequiredByNewIlstAtom);
            writeDataUptoIncludingIlst(fileReadChannel, fileWriteChannel, ilstHeader.getLength(), (int)ilstHeader.getFilePos(), newIlstData);

            //Write the modified free atom that comes after ilst
            int newFreeSize = sizeOfExistingMetaLevelFreeAtom + (ilstHeader.getLength() - sizeRequiredByNewIlstAtom);
            Mp4FreeBox newFreeBox = new Mp4FreeBox(newFreeSize - Mp4BoxHeader.HEADER_LENGTH);
            fileWriteChannel.write(newFreeBox.getHeader().getHeaderData());
            fileWriteChannel.write(newFreeBox.getData());

            //Skip over the read channel old free atom
            fileReadChannel.position(fileReadChannel.position() + sizeOfExistingMetaLevelFreeAtom);
            writeDataAfterIlst(fileReadChannel, fileWriteChannel, neroTagsHeader);
        }
        //No free atom we need to create a new one or adjust top level free atom
        else
        {
            int newFreeSize = (ilstHeader.getLength() - sizeRequiredByNewIlstAtom) - Mp4BoxHeader.HEADER_LENGTH;
            //We need to create a new one, so dont have to adjust all the headers but only works if the size
            //of tags has decreased by more 8 characters so there is enough room for the free boxes header we take
            //into account size of new header in calculating size of box
            if (newFreeSize > 0)
            {
                logger.config("Writing:Option 3:Smaller Size can create free atom");
                writeDataUptoIncludingIlst(fileReadChannel, fileWriteChannel, ilstHeader.getLength(), (int)ilstHeader.getFilePos(), newIlstData);

                //Create new free box
                Mp4FreeBox newFreeBox = new Mp4FreeBox(newFreeSize);
                fileWriteChannel.write(newFreeBox.getHeader().getHeaderData());
                fileWriteChannel.write(newFreeBox.getData());
                writeDataAfterIlst(fileReadChannel, fileWriteChannel, neroTagsHeader);
            }
            //Ok everything in this bit of tree has to be recalculated because eight or less bytes smaller
            else
            {
                logger.config("Writing:Option 4:Smaller Size <=8 cannot create free atoms");

                //Size will be this amount smaller
                int sizeReducedBy = ilstHeader.getLength() - sizeRequiredByNewIlstAtom;

                //Write stuff before Moov (ftyp)
                fileReadChannel.position(0);
                fileWriteChannel.transferFrom(fileReadChannel, 0, moovHeader.getFilePos());
                fileWriteChannel.position(moovHeader.getFilePos());

                //Edit stco atom within moov header,  we need to adjust offsets by the amount mdat is going to be shifted
                //unless mdat is at start of file
                if (mdatHeader.getFilePos() > moovHeader.getFilePos())
                {
                    stco.adjustOffsets(-sizeReducedBy);
                }

                //Edit and rewrite the Moov,Udta and Meta header in moov buffer
                adjustSizeOfMoovHeader(moovHeader, moovBuffer, -sizeReducedBy, udtaHeader, metaHeader);
                fileWriteChannel.write(moovHeader.getHeaderData());
                moovBuffer.rewind();
                moovBuffer.limit(relativeIlstposition);
                fileWriteChannel.write(moovBuffer);

                //Now write ilst data
                fileWriteChannel.write(newIlstData);
                fileReadChannel.position(ilstHeader.getFilePos() + ilstHeader.getLength());
                writeDataAfterIlst(fileReadChannel, fileWriteChannel, neroTagsHeader);
            }
        }
    }

    /**
     * We can fit the metadata in under the meta item just by using some of the padding available in the free
     * atom under the meta atom need to take of the side of free header otherwise might end up with
     * solution where can fit in data, but cant fit in free atom header
     *
     * @param fileReadChannel
     * @param fileWriteChannel
     * @param sizeOfExistingIlstAtom
     * @param tagsHeader
     * @param sizeOfExistingMetaLevelFreeAtom
     * @param positionOfNewIlstWithinFile
     * @param newIlstData
     * @param additionalSpaceRequiredForMetadata
     * @throws IOException
     * @throws CannotWriteException
     */
    private void writeNewMetadataLargerButCanUseFreeAtom(FileChannel fileReadChannel, FileChannel fileWriteChannel, int sizeOfExistingIlstAtom, Mp4BoxHeader tagsHeader, int sizeOfExistingMetaLevelFreeAtom, int positionOfNewIlstWithinFile, ByteBuffer newIlstData, int additionalSpaceRequiredForMetadata) throws IOException, CannotWriteException
    {
        int newFreeSize = sizeOfExistingMetaLevelFreeAtom - (additionalSpaceRequiredForMetadata);
        logger.config("Writing:Option 5;Larger Size can use meta free atom need extra:" + newFreeSize + "bytes");

        writeDataUptoIncludingIlst(fileReadChannel, fileWriteChannel, sizeOfExistingIlstAtom, positionOfNewIlstWithinFile, newIlstData);

        //Create an amended smaller freeBaos atom and write it to file
        Mp4FreeBox newFreeBox = new Mp4FreeBox(newFreeSize - Mp4BoxHeader.HEADER_LENGTH);
        fileWriteChannel.write(newFreeBox.getHeader().getHeaderData());
        fileWriteChannel.write(newFreeBox.getData());

        //Skip over the read channel old free atom
        fileReadChannel.position(fileReadChannel.position() + sizeOfExistingMetaLevelFreeAtom);
        writeDataAfterIlst(fileReadChannel, fileWriteChannel, tagsHeader);
    }

    /**
     * Write tag to rafTemp file
     *
     * @param tag     tag data
     * @param raf     current file
     * @param rafTemp temporary file for writing
     * @throws CannotWriteException
     * @throws IOException
     */
    public void write(Tag tag, RandomAccessFile raf, RandomAccessFile rafTemp) throws CannotWriteException, IOException
    {
        logger.config("Started writing tag data");
        FileChannel fileReadChannel = raf.getChannel();
        FileChannel fileWriteChannel = rafTemp.getChannel();

        int sizeOfExistingIlstAtom = 0;
        int sizeRequiredByNewIlstAtom;
        int positionOfNewIlstAtomRelativeToMoovAtom;
        int positionOfNewIlstWithinFile;
        int sizeOfExistingMetaLevelFreeAtom;
        int positionOfTopLevelFreeAtom;
        int sizeOfExistingTopLevelFreeAtom;
        long endOfMoov = 0;
        //Found top level free atom that comes after moov and before mdat, (also true if no free atom ?)
        boolean topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata;
        Mp4BoxHeader topLevelFreeHeader;
        Mp4AtomTree atomTree;

        //Build AtomTree
        try
        {
            atomTree = new Mp4AtomTree(raf, false);
        }
        catch (CannotReadException cre)
        {
            throw new CannotWriteException(cre.getMessage());
        }

        Mp4BoxHeader mdatHeader = atomTree.getBoxHeader(atomTree.getMdatNode());
        //Unable to find audio so no chance of saving any changes
        if (mdatHeader == null)
        {
            throw new CannotWriteException(ErrorMessage.MP4_CHANGES_TO_FILE_FAILED_CANNOT_FIND_AUDIO.getMsg());
        }

        //Go through every field constructing the data that will appear starting from ilst box
        ByteBuffer newIlstData = tc.convert(tag);
        newIlstData.rewind();
        sizeRequiredByNewIlstAtom = newIlstData.limit();

        //Moov Box header
        Mp4BoxHeader moovHeader = atomTree.getBoxHeader(atomTree.getMoovNode());
        endOfMoov = moovHeader.getFilePos() + moovHeader.getLength();

        Mp4StcoBox stco = atomTree.getStco();
        Mp4BoxHeader ilstHeader = atomTree.getBoxHeader(atomTree.getIlstNode());
        Mp4BoxHeader udtaHeader = atomTree.getBoxHeader(atomTree.getUdtaNode());
        Mp4BoxHeader metaHeader = atomTree.getBoxHeader(atomTree.getMetaNode());
        Mp4BoxHeader hdlrMetaHeader = atomTree.getBoxHeader(atomTree.getHdlrWithinMetaNode());
        Mp4BoxHeader neroTagsHeader = atomTree.getBoxHeader(atomTree.getTagsNode());
        Mp4BoxHeader trakHeader = atomTree.getBoxHeader(atomTree.getTrakNodes().get(0));
        ByteBuffer moovBuffer = atomTree.getMoovBuffer();


        //Work out if we/what kind of metadata hierachy we currently have in the file
        //Udta
        if (udtaHeader != null)
        {
            //Meta
            if (metaHeader != null)
            {
                //ilst - record where ilst is,and where it ends
                if (ilstHeader != null)
                {
                    sizeOfExistingIlstAtom = ilstHeader.getLength();

                    //Relative means relative to moov buffer after moov header
                    positionOfNewIlstWithinFile = (int) ilstHeader.getFilePos();
                    positionOfNewIlstAtomRelativeToMoovAtom = (int) (positionOfNewIlstWithinFile - (moovHeader.getFilePos() + Mp4BoxHeader.HEADER_LENGTH));
                }
                else
                {
                    //Place ilst immediately after existing hdlr atom
                    if (hdlrMetaHeader != null)
                    {
                        positionOfNewIlstWithinFile = (int) hdlrMetaHeader.getFilePos() + hdlrMetaHeader.getLength();
                        positionOfNewIlstAtomRelativeToMoovAtom = (int) (positionOfNewIlstWithinFile - (moovHeader.getFilePos() + Mp4BoxHeader.HEADER_LENGTH));
                    }
                    //Place ilst after data fields in meta atom
                    //TODO Should we create a hdlr atom
                    else
                    {
                        positionOfNewIlstWithinFile = (int) metaHeader.getFilePos() + Mp4BoxHeader.HEADER_LENGTH + Mp4MetaBox.FLAGS_LENGTH;
                        positionOfNewIlstAtomRelativeToMoovAtom = (int) ((positionOfNewIlstWithinFile) - (moovHeader.getFilePos() + Mp4BoxHeader.HEADER_LENGTH));
                    }
                }
            }
            else
            {
                //There no ilst or meta header so we set to position where it would be if it existed
                positionOfNewIlstAtomRelativeToMoovAtom = moovHeader.getLength() - Mp4BoxHeader.HEADER_LENGTH;
                positionOfNewIlstWithinFile = (int) (moovHeader.getFilePos() + moovHeader.getLength());
            }
        }
        //There no udta header so we are going to create a new structure, but we have to be aware that there might be
        //an existing meta box structure in which case we preserve it but with our new structure before it.
        else
        {
            //Create new structure just after the end of the trak atom
            if (metaHeader != null)
            {
                positionOfNewIlstWithinFile = (int) trakHeader.getFilePos() + trakHeader.getLength();
                positionOfNewIlstAtomRelativeToMoovAtom = (int) (positionOfNewIlstWithinFile - (moovHeader.getFilePos() + Mp4BoxHeader.HEADER_LENGTH));
            }
            else
            {
                //There no udta,ilst or meta header so we set to position where it would be if it existed
                positionOfNewIlstWithinFile = (int) (moovHeader.getFilePos() + moovHeader.getLength());
                positionOfNewIlstAtomRelativeToMoovAtom = moovHeader.getLength() - Mp4BoxHeader.HEADER_LENGTH;
            }
        }

        //Find size of Level-4 Free atom (if any) immediately after ilst atom
        sizeOfExistingMetaLevelFreeAtom = getMetaLevelFreeAtomSize(atomTree);


        //Level-1 free atom
        positionOfTopLevelFreeAtom = 0;
        sizeOfExistingTopLevelFreeAtom = 0;
        topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata = true;
        for (DefaultMutableTreeNode freeNode : atomTree.getFreeNodes())
        {
            DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) freeNode.getParent();
            if (parentNode.isRoot())
            {
                topLevelFreeHeader = ((Mp4BoxHeader) freeNode.getUserObject());
                sizeOfExistingTopLevelFreeAtom = topLevelFreeHeader.getLength();
                positionOfTopLevelFreeAtom = (int) topLevelFreeHeader.getFilePos();
                break;
            }
        }

        if (sizeOfExistingTopLevelFreeAtom > 0)
        {
            if (positionOfTopLevelFreeAtom > mdatHeader.getFilePos())
            {
                topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata = false;
            }
            else if (positionOfTopLevelFreeAtom < moovHeader.getFilePos())
            {
                topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata = false;
            }
        }
        else
        {
            positionOfTopLevelFreeAtom = (int) mdatHeader.getFilePos();
        }

        logger.config("Read header successfully ready for writing");
        //The easiest option since no difference in the size of the metadata so all we have to do is
        //create a new file identical to first file but with replaced ilst
        if (sizeOfExistingIlstAtom == sizeRequiredByNewIlstAtom)
        {
            writeMetadataSameSize(fileReadChannel, fileWriteChannel, ilstHeader, newIlstData, neroTagsHeader);
        }
        //.. we just need to increase the size of the free atom below the meta atom, and replace the metadata
        //no other changes necessary and total file size remains the same
        else if (sizeOfExistingIlstAtom > sizeRequiredByNewIlstAtom)
        {
            writeOldMetadataLargerThanNewMetadata(fileReadChannel,
                    fileWriteChannel,
                    sizeOfExistingMetaLevelFreeAtom,
                    neroTagsHeader,
                    positionOfNewIlstAtomRelativeToMoovAtom,
                    newIlstData,
                    moovHeader,
                    moovBuffer,
                    mdatHeader,
                    stco,
                    udtaHeader,
                    metaHeader,
                    ilstHeader);
        }
        //Size of metadata has increased, the most complex situation, more atoms affected
        else
        {
            int additionalSpaceRequiredForMetadata = sizeRequiredByNewIlstAtom - sizeOfExistingIlstAtom;
            if (additionalSpaceRequiredForMetadata <= (sizeOfExistingMetaLevelFreeAtom - Mp4BoxHeader.HEADER_LENGTH))
            {
                writeNewMetadataLargerButCanUseFreeAtom(
                        fileReadChannel,
                        fileWriteChannel,
                        sizeOfExistingIlstAtom,
                        neroTagsHeader,
                        sizeOfExistingMetaLevelFreeAtom,
                        positionOfNewIlstWithinFile,
                        newIlstData,
                        additionalSpaceRequiredForMetadata);
            }
            //There is not enough padding in the metadata free atom anyway
            else
            {
                int additionalMetaSizeThatWontFitWithinMetaAtom = additionalSpaceRequiredForMetadata - (sizeOfExistingMetaLevelFreeAtom);

                //Write stuff before Moov (ftyp)
                writeUpToMoovHeader(fileReadChannel, fileWriteChannel, moovHeader);
                if (udtaHeader == null)
                {
                    writeNoExistingUdtaAtom(fileWriteChannel, newIlstData,
                                            moovHeader,
                                            moovBuffer,
                                            mdatHeader,
                                            stco, sizeOfExistingTopLevelFreeAtom,
                                            topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata);
                }
                else if (metaHeader == null)
                {
                    writeNoExistingMetaAtom(udtaHeader,
                            fileWriteChannel,
                            positionOfNewIlstAtomRelativeToMoovAtom,
                            newIlstData,
                            moovHeader,
                            moovBuffer,
                            mdatHeader,
                            stco, sizeOfExistingTopLevelFreeAtom,
                            topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata);
                }
                else
                {
                    writeHaveExistingMetadata(udtaHeader,
                            metaHeader,
                            fileWriteChannel,
                            positionOfNewIlstAtomRelativeToMoovAtom, moovHeader,
                            moovBuffer,
                            mdatHeader,
                            stco,
                            additionalMetaSizeThatWontFitWithinMetaAtom,
                            sizeOfExistingTopLevelFreeAtom,
                            topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata);
                }

                writeNewDataWhenWeMoreMetadataThanExisting(fileReadChannel,
                        fileWriteChannel,
                        newIlstData,
                        additionalMetaSizeThatWontFitWithinMetaAtom,
                        sizeOfExistingTopLevelFreeAtom,
                        topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata,
                        endOfMoov, neroTagsHeader,
                        sizeOfExistingIlstAtom,
                        positionOfNewIlstWithinFile,
                        sizeOfExistingMetaLevelFreeAtom,
                        positionOfTopLevelFreeAtom);
            }
        }
        //Close all channels to original file
        fileReadChannel.close();
        raf.close();

        //Ensure we have written correctly, reject if not
        checkFileWrittenCorrectly(rafTemp, mdatHeader, fileWriteChannel, stco);
    }

    private void writeUpToMoovHeader(FileChannel fileReadChannel,
                                     FileChannel fileWriteChannel,
                                     Mp4BoxHeader moovHeader)
            throws IOException, CannotWriteException
    {
        //Write stuff before Moov (ftyp)
        fileReadChannel.position(0);
        fileWriteChannel.transferFrom(fileReadChannel, 0, moovHeader.getFilePos());
        fileWriteChannel.position(moovHeader.getFilePos());
    }

    /**
     * #385 Write data in chunks, needed if writing large amounts of data
     *
     * @param fileReadChannel
     * @param fileWriteChannel
     * @throws IOException
     * @throws CannotWriteException
     */
    private void writeDataInChunks(FileChannel fileReadChannel, FileChannel fileWriteChannel) throws IOException, CannotWriteException
    {
        long amountToBeWritten = fileReadChannel.size() - fileReadChannel.position();
        long written = 0;
        long chunksize = TagOptionSingleton.getInstance().getWriteChunkSize();
        long count = amountToBeWritten / chunksize;

        long mod = amountToBeWritten % chunksize;
        for (int i = 0; i < count; i++)
        {
            written += fileWriteChannel.transferFrom(fileReadChannel, fileWriteChannel.position(), chunksize);
            fileWriteChannel.position(fileWriteChannel.position() + chunksize);
        }
        written += fileWriteChannel.transferFrom(fileReadChannel, fileWriteChannel.position(), mod);
        if (written != amountToBeWritten)
        {
            throw new CannotWriteException("Was meant to write " + amountToBeWritten + " bytes but only written " + written + " bytes");
        }
    }

    /**
     * Replace tags atom (and children) by a free atom
     *
     * @param fileWriteChannel
     * @param tagsHeader
     * @throws IOException
     */
    private void convertandWriteTagsAtomToFreeAtom(FileChannel fileWriteChannel, Mp4BoxHeader tagsHeader) throws IOException
    {
        Mp4FreeBox freeBox = new Mp4FreeBox(tagsHeader.getDataLength());
        fileWriteChannel.write(freeBox.getHeader().getHeaderData());
        fileWriteChannel.write(freeBox.getData());
    }

    /**
     * Write the data including new ilst
     * <p>can be used as long as we dont have to adjust the size of moov header
     *
     * @param fileReadChannel
     * @param fileWriteChannel
     * @param sizeOfExistingIlstAtom
     * @param startIlstWithinFile
     * @param newIlstAtomData
     * @throws IOException
     */
    private void writeDataUptoIncludingIlst(FileChannel fileReadChannel, FileChannel fileWriteChannel, int sizeOfExistingIlstAtom, int startIlstWithinFile, ByteBuffer newIlstAtomData) throws IOException
    {
        fileReadChannel.position(0);
        fileWriteChannel.transferFrom(fileReadChannel, 0, startIlstWithinFile);
        fileWriteChannel.position(startIlstWithinFile);
        fileWriteChannel.write(newIlstAtomData);
        fileReadChannel.position(startIlstWithinFile + sizeOfExistingIlstAtom);
    }

    /**
     * Write data after ilst upto the end of the file
     * <p/>
     * <p>Can be used if dont need to adjust size of moov header of modify top level free atoms
     *
     * @param fileReadChannel
     * @param fileWriteChannel
     * @param tagsHeader
     * @throws IOException
     */
    private void writeDataAfterIlst(FileChannel fileReadChannel, FileChannel fileWriteChannel, Mp4BoxHeader tagsHeader) throws IOException, CannotWriteException
    {
        if (tagsHeader != null)
        {
            //Write from after free upto tags atom
            writeNeroData(fileReadChannel, fileWriteChannel, tagsHeader);
        }
        else
        {
            //Now write the rest of the file which won't have changed
            writeDataInChunks(fileReadChannel, fileWriteChannel);
        }
    }

    /**
     * Determine the size of the free atom immediately after ilst atom at the same level (if any), we can use this if
     * ilst needs to grow or shrink because of more less metadata
     *
     * @param atomTree
     * @return
     */
    private int getMetaLevelFreeAtomSize(Mp4AtomTree atomTree)
    {
        int oldMetaLevelFreeAtomSize;//Level 4 - Free
        oldMetaLevelFreeAtomSize = 0;

        for (DefaultMutableTreeNode freeNode : atomTree.getFreeNodes())
        {
            DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) freeNode.getParent();
            DefaultMutableTreeNode brotherNode = freeNode.getPreviousSibling();
            if (!parentNode.isRoot())
            {
                Mp4BoxHeader parentHeader = ((Mp4BoxHeader) parentNode.getUserObject());
                Mp4BoxHeader freeHeader = ((Mp4BoxHeader) freeNode.getUserObject());

                //We are only interested in free atoms at this level if they come after the ilst node
                if (brotherNode != null)
                {
                    Mp4BoxHeader brotherHeader = ((Mp4BoxHeader) brotherNode.getUserObject());

                    if (parentHeader.getId().equals(Mp4AtomIdentifier.META.getFieldName()) && brotherHeader.getId().equals(Mp4AtomIdentifier.ILST.getFieldName()))
                    {
                        oldMetaLevelFreeAtomSize = freeHeader.getLength();
                        break;
                    }
                }
            }
        }
        return oldMetaLevelFreeAtomSize;
    }

    /**
     * Check File Written Correctly
     *
     * @param rafTemp
     * @param mdatHeader
     * @param fileWriteChannel
     * @param stco
     * @throws CannotWriteException
     * @throws IOException
     */
    private void checkFileWrittenCorrectly(RandomAccessFile rafTemp, Mp4BoxHeader mdatHeader, FileChannel fileWriteChannel, Mp4StcoBox stco) throws CannotWriteException, IOException
    {

        logger.config("Checking file has been written correctly");

        try
        {
            //Create a tree from the new file
            Mp4AtomTree newAtomTree;
            newAtomTree = new Mp4AtomTree(rafTemp, false);

            //Check we still have audio data file, and check length
            Mp4BoxHeader newMdatHeader = newAtomTree.getBoxHeader(newAtomTree.getMdatNode());
            if (newMdatHeader == null)
            {
                throw new CannotWriteException(ErrorMessage.MP4_CHANGES_TO_FILE_FAILED_NO_DATA.getMsg());
            }
            if (newMdatHeader.getLength() != mdatHeader.getLength())
            {
                throw new CannotWriteException(ErrorMessage.MP4_CHANGES_TO_FILE_FAILED_DATA_CORRUPT.getMsg());
            }

            //Should always have udta atom after writing to file
            Mp4BoxHeader newUdtaHeader = newAtomTree.getBoxHeader(newAtomTree.getUdtaNode());
            if (newUdtaHeader == null)
            {
                throw new CannotWriteException(ErrorMessage.MP4_CHANGES_TO_FILE_FAILED_NO_TAG_DATA.getMsg());
            }

            //Should always have meta atom after writing to file
            Mp4BoxHeader newMetaHeader = newAtomTree.getBoxHeader(newAtomTree.getMetaNode());
            if (newMetaHeader == null)
            {
                throw new CannotWriteException(ErrorMessage.MP4_CHANGES_TO_FILE_FAILED_NO_TAG_DATA.getMsg());
            }

            //Check offsets are correct, may not match exactly in original file so just want to make
            //sure that the discrepancy if any is preserved
            Mp4StcoBox newStco = newAtomTree.getStco();

            logger.finer("stco:Original First Offset" + stco.getFirstOffSet());
            logger.finer("stco:Original Diff" + (int) (stco.getFirstOffSet() - mdatHeader.getFilePos()));
            logger.finer("stco:Original Mdat Pos" + mdatHeader.getFilePos());
            logger.finer("stco:New First Offset" + newStco.getFirstOffSet());
            logger.finer("stco:New Diff" + (int) ((newStco.getFirstOffSet() - newMdatHeader.getFilePos())));
            logger.finer("stco:New Mdat Pos" + newMdatHeader.getFilePos());
            int diff = (int) (stco.getFirstOffSet() - mdatHeader.getFilePos());
            if ((newStco.getFirstOffSet() - newMdatHeader.getFilePos()) != diff)
            {
                int discrepancy = (int) ((newStco.getFirstOffSet() - newMdatHeader.getFilePos()) - diff);
                throw new CannotWriteException(ErrorMessage.MP4_CHANGES_TO_FILE_FAILED_INCORRECT_OFFSETS.getMsg(discrepancy));
            }
        }
        catch (Exception e)
        {
            if (e instanceof CannotWriteException)
            {
                throw (CannotWriteException) e;
            }
            else
            {
                e.printStackTrace();
                throw new CannotWriteException(ErrorMessage.MP4_CHANGES_TO_FILE_FAILED.getMsg() + ":" + e.getMessage());
            }
        }
        finally
        {
            //Close references to new file
            rafTemp.close();
            fileWriteChannel.close();
        }
        logger.config("File has been written correctly");
    }

    /**
     * Delete the tag
     * <p/>
     * <p/>
     * <p>This is achieved by writing an empty ilst atom
     *
     * @param raf
     * @param rafTemp
     * @throws IOException
     */
    public void delete(RandomAccessFile raf, RandomAccessFile rafTemp) throws IOException
    {
        Mp4Tag tag = new Mp4Tag();

        try
        {
            write(tag, raf, rafTemp);
        }
        catch (CannotWriteException cwe)
        {
            throw new IOException(cwe.getMessage());
        }
    }

    /**
     * Use when we need to write metadata and there is no existing udta atom so we have to create the complete
     * udta/metadata structure
     *
     * @param fileWriteChannel
     * @param newIlstData
     * @param moovHeader
     * @param moovBuffer
     * @param mdatHeader
     * @param stco
     * @param sizeOfExistingTopLevelFreeAtom
     * @param topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata
     * @throws IOException
     * @throws CannotWriteException
     */
    private void writeNoExistingUdtaAtom(FileChannel fileWriteChannel,
                                         ByteBuffer newIlstData,
                                         Mp4BoxHeader moovHeader,
                                         ByteBuffer moovBuffer,
                                         Mp4BoxHeader mdatHeader,
                                         Mp4StcoBox stco,
                                         int sizeOfExistingTopLevelFreeAtom,
                                         boolean topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata)
            throws IOException, CannotWriteException

    {
        logger.severe("Writing:Option 5.1;No udta atom");

        Mp4HdlrBox hdlrBox = Mp4HdlrBox.createiTunesStyleHdlrBox();
        Mp4MetaBox metaBox = Mp4MetaBox.createiTunesStyleMetaBox(hdlrBox.getHeader().getLength() + newIlstData.limit());
        Mp4BoxHeader udtaHeader = new Mp4BoxHeader(Mp4AtomIdentifier.UDTA.getFieldName());
        udtaHeader.setLength(Mp4BoxHeader.HEADER_LENGTH + metaBox.getHeader().getLength());

        adjustStcoIfNoSuitableLargeEnoughTopLevelAtom(
                sizeOfExistingTopLevelFreeAtom,
                topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata,
                udtaHeader.getLength(),
                stco,
                moovHeader,
                mdatHeader);

        //Edit the Moov header to length and rewrite to account for new udta atom
        moovHeader.setLength(moovHeader.getLength() + udtaHeader.getLength());
        fileWriteChannel.write(moovHeader.getHeaderData());
        moovBuffer.rewind();
        fileWriteChannel.write(moovBuffer);

        //Write new atoms required for holding metadata in itunes format
        fileWriteChannel.write(udtaHeader.getHeaderData());
        fileWriteChannel.write(metaBox.getHeader().getHeaderData());
        fileWriteChannel.write(metaBox.getData());
        fileWriteChannel.write(hdlrBox.getHeader().getHeaderData());
        fileWriteChannel.write(hdlrBox.getData());
    }

    /**
     * Use when we need to write metadata, we have a udta atom but there is no existing meta atom so we have to create the complete
     * metadata structure
     *
     * @param fileWriteChannel
     * @param positionOfNewIlstAtomRelativeToMoovAtom
     * @param newIlstData
     * @param moovHeader
     * @param moovBuffer
     * @param mdatHeader
     * @param stco
     * @param sizeOfExistingTopLevelFreeAtom
     * @param topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata
     * @throws IOException
     * @throws CannotWriteException
     */
    private void writeNoExistingMetaAtom(Mp4BoxHeader udtaHeader,
                                         FileChannel fileWriteChannel,
                                         int positionOfNewIlstAtomRelativeToMoovAtom,
                                         ByteBuffer newIlstData,
                                         Mp4BoxHeader moovHeader,
                                         ByteBuffer moovBuffer,
                                         Mp4BoxHeader mdatHeader,
                                         Mp4StcoBox stco,
                                         int sizeOfExistingTopLevelFreeAtom,
                                         boolean topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata) throws IOException, CannotWriteException

    {
        //Create a new udta atom
        logger.severe("Writing:Option 5.2;No meta atom");

        int newIlstDataSize = newIlstData.limit();

        //Udta didnt have a meta atom but it may have some other data we want to preserve (I think)
        int existingUdtaDataLength = udtaHeader.getDataLength();

        Mp4HdlrBox hdlrBox = Mp4HdlrBox.createiTunesStyleHdlrBox();
        Mp4MetaBox metaBox = Mp4MetaBox.createiTunesStyleMetaBox(hdlrBox.getHeader().getLength() + newIlstDataSize);
        udtaHeader = new Mp4BoxHeader(Mp4AtomIdentifier.UDTA.getFieldName());
        udtaHeader.setLength(Mp4BoxHeader.HEADER_LENGTH + metaBox.getHeader().getLength() + existingUdtaDataLength);

        int increaseInSizeOfUdtaAtom = udtaHeader.getDataLength() - existingUdtaDataLength;

        adjustStcoIfNoSuitableLargeEnoughTopLevelAtom(
                sizeOfExistingTopLevelFreeAtom,
                topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata,
                increaseInSizeOfUdtaAtom,
                stco,
                moovHeader,
                mdatHeader);

        //Edit and rewrite the Moov header upto Udta
        moovHeader.setLength(moovHeader.getLength() + increaseInSizeOfUdtaAtom);
        fileWriteChannel.write(moovHeader.getHeaderData());
        moovBuffer.rewind();
        moovBuffer.limit(positionOfNewIlstAtomRelativeToMoovAtom - (existingUdtaDataLength + Mp4BoxHeader.HEADER_LENGTH));
        fileWriteChannel.write(moovBuffer);

        //Write new atoms required for holding metadata in iTunes format
        fileWriteChannel.write(udtaHeader.getHeaderData());

        //Write any atoms if they previously existed within udta atom
        if(moovBuffer.position() + Mp4BoxHeader.HEADER_LENGTH < moovBuffer.capacity())
        {
            moovBuffer.limit(moovBuffer.capacity());
            moovBuffer.position(moovBuffer.position() + Mp4BoxHeader.HEADER_LENGTH);
            fileWriteChannel.write(moovBuffer);
        }

        //Write our newly constructed meta/hdlr headers (required for ilst)
        fileWriteChannel.write(metaBox.getHeader().getHeaderData());
        fileWriteChannel.write(metaBox.getData());
        fileWriteChannel.write(hdlrBox.getHeader().getHeaderData());
        fileWriteChannel.write(hdlrBox.getData());
    }

    /**
     * We have existing structure but we need more space
     *
     * @param udtaHeader
     * @param fileWriteChannel
     * @param positionOfNewIlstAtomRelativeToMoovAtom
     * @param moovHeader
     * @param moovBuffer
     * @param mdatHeader
     * @param stco
     * @param additionalMetaSizeThatWontFitWithinMetaAtom
     * @param topLevelFreeSize
     * @param topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata
     * @throws IOException
     * @throws CannotWriteException
     */
    private void writeHaveExistingMetadata(Mp4BoxHeader udtaHeader,
                                           Mp4BoxHeader metaHeader,
                                           FileChannel fileWriteChannel,
                                           int positionOfNewIlstAtomRelativeToMoovAtom,
                                           Mp4BoxHeader moovHeader,
                                           ByteBuffer moovBuffer,
                                           Mp4BoxHeader mdatHeader,
                                           Mp4StcoBox stco,
                                           int additionalMetaSizeThatWontFitWithinMetaAtom,
                                           int topLevelFreeSize,
                                           boolean topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata)
            throws IOException, CannotWriteException
    {
        logger.severe("Writing:Option 5.3;udta and meta atom exists");

        adjustStcoIfNoSuitableLargeEnoughTopLevelAtom(
                topLevelFreeSize,
                topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata,
                additionalMetaSizeThatWontFitWithinMetaAtom,
                stco,
                moovHeader,
                mdatHeader);

        //Edit and rewrite the Moov header inc udta and meta headers)
        adjustSizeOfMoovHeader(moovHeader, moovBuffer, additionalMetaSizeThatWontFitWithinMetaAtom, udtaHeader, metaHeader);
        fileWriteChannel.write(moovHeader.getHeaderData());

        //Now write from this edited buffer up until location of start of ilst atom
        moovBuffer.rewind();
        moovBuffer.limit(positionOfNewIlstAtomRelativeToMoovAtom);
        fileWriteChannel.write(moovBuffer);
    }

    /**
     * Need to rewrite the stco offsets if the location of mdat (audio) header is going to move
     *
     * @param topLevelFreeSize
     * @param topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata
     * @param additionalSizeRequired
     * @param stco
     * @param moovHeader
     * @param mdatHeader
     */
    private void adjustStcoIfNoSuitableLargeEnoughTopLevelAtom( int topLevelFreeSize,
                                                                boolean topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata,
                                                                int additionalSizeRequired,
                                                                Mp4StcoBox stco,
                                                                Mp4BoxHeader moovHeader,
                                                                Mp4BoxHeader mdatHeader)
    {
        //We don't bother using the top level free atom coz not big enough anyway, we need to adjust offsets
        //by the amount mdat is going to be shifted as long as mdat is after moov
        if (mdatHeader.getFilePos() > moovHeader.getFilePos())
        {
            //Edit stco atom within moov header, if the free atom comes after mdat OR
            //(there is not enough space in the top level free atom
            //or special case of matching exactly the free atom plus header)
            if ((!topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata) ||
                    ((topLevelFreeSize - Mp4BoxHeader.HEADER_LENGTH < additionalSizeRequired)
                            && (topLevelFreeSize != additionalSizeRequired)))
            {
                stco.adjustOffsets(additionalSizeRequired);
            }
        }
    }

    /**
     * If we have more new metadata than previously this is where we actually write the metadata
     *
     * @param fileReadChannel
     * @param fileWriteChannel
     * @param newIlstData
     * @param additionalMetaSizeThatWontFitWithinMetaAtom
     * @param topLevelFreeSize
     * @param topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata
     * @param endOfMoov
     * @param tagsHeader
     * @param oldIlstSize
     * @param startIlstWithinFile
     *
     * @param oldMetaLevelFreeAtomSize
     * @param topLevelFreePosition
     * @throws IOException
     * @throws CannotWriteException
     */
    private void writeNewDataWhenWeMoreMetadataThanExisting(FileChannel fileReadChannel,
                                                            FileChannel fileWriteChannel,
                                                            ByteBuffer newIlstData,
                                                            int additionalMetaSizeThatWontFitWithinMetaAtom,
                                                            int topLevelFreeSize,
                                                            boolean topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata,
                                                            long endOfMoov,
                                                            Mp4BoxHeader tagsHeader,
                                                            int oldIlstSize,
                                                            int startIlstWithinFile,
                                                            int oldMetaLevelFreeAtomSize, int topLevelFreePosition

    ) throws IOException, CannotWriteException
    {
        //Now write ilst data
        fileWriteChannel.write(newIlstData);

        //Skip over the read channel old meta level free atom because now used up
        fileReadChannel.position(startIlstWithinFile + oldIlstSize);
        fileReadChannel.position(fileReadChannel.position() + oldMetaLevelFreeAtomSize);

        if (tagsHeader != null)
        {
            //Write from after ilst upto tags atom
            long writeBetweenIlstAndTags = tagsHeader.getFilePos() - fileReadChannel.position();
            fileWriteChannel.transferFrom(fileReadChannel, fileWriteChannel.position(), writeBetweenIlstAndTags);
            fileWriteChannel.position(fileWriteChannel.position() + writeBetweenIlstAndTags);
            convertandWriteTagsAtomToFreeAtom(fileWriteChannel, tagsHeader);

            //Write after tags atom upto end of moov
            fileReadChannel.position(tagsHeader.getFilePos() + tagsHeader.getLength());
            long extraData = endOfMoov - fileReadChannel.position();
            fileWriteChannel.transferFrom(fileReadChannel, fileWriteChannel.position(), extraData);
        }
        else
        {
            //Now write the rest of children under moov which wont have changed
            long extraData = endOfMoov - fileReadChannel.position();
            fileWriteChannel.transferFrom(fileReadChannel, fileWriteChannel.position(), extraData);
            fileWriteChannel.position(fileWriteChannel.position() + extraData);
        }

        //If we have top level free atom that comes before mdat we might be able to use it but only if
        //the free atom actually come after the the metadata
        if (topLevelFreeAtomComesBeforeMdatAtomAndAfterMetadata && (topLevelFreePosition >= startIlstWithinFile))
        {
            //If the shift is less than the space available in this second free atom data size we should
            //minimize the free atom accordingly (then we don't have to update stco atom)
            //note could be a double negative as additionalMetaSizeThatWontFitWithinMetaAtom could be -1 to -8 but thats ok stills works
            //ok
            if (topLevelFreeSize - Mp4BoxHeader.HEADER_LENGTH >= additionalMetaSizeThatWontFitWithinMetaAtom)
            {
                logger.config("Writing:Option 6;Larger Size can use top free atom");
                Mp4FreeBox freeBox = new Mp4FreeBox((topLevelFreeSize - Mp4BoxHeader.HEADER_LENGTH) - additionalMetaSizeThatWontFitWithinMetaAtom);
                fileWriteChannel.write(freeBox.getHeader().getHeaderData());
                fileWriteChannel.write(freeBox.getData());

                //Skip over the read channel old free atom
                fileReadChannel.position(fileReadChannel.position() + topLevelFreeSize);

                //Write Mdat
                writeDataInChunks(fileReadChannel, fileWriteChannel);
            }
            //If the space required is identical to total size of the free space (inc header)
            //we could just remove the header
            else if (topLevelFreeSize == additionalMetaSizeThatWontFitWithinMetaAtom)
            {
                logger.config("Writing:Option 7;Larger Size uses top free atom including header");
                //Skip over the read channel old free atom
                fileReadChannel.position(fileReadChannel.position() + topLevelFreeSize);

                //Write Mdat
                writeDataInChunks(fileReadChannel, fileWriteChannel);
            }
            //Mdat is going to have to move anyway, so keep free atom as is and write it and mdat
            //(have already updated stco above)
            else
            {
                logger.config("Writing:Option 8;Larger Size cannot use top free atom");
                fileWriteChannel.transferFrom(fileReadChannel, fileWriteChannel.position(), fileReadChannel.size() - fileReadChannel.position());
                writeDataInChunks(fileReadChannel, fileWriteChannel);
            }
        }
        else
        {
            logger.config("Writing:Option 9;Top Level Free comes after Mdat or before Metadata so cant use it");
            writeDataInChunks(fileReadChannel, fileWriteChannel);
        }
    }
}
