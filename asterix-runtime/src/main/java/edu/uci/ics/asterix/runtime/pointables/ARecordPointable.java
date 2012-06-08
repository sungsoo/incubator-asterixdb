/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.asterix.runtime.pointables;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.dataflow.data.nontagged.AqlNullWriterFactory;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AInt32SerializerDeserializer;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.AUnionType;
import edu.uci.ics.asterix.om.types.EnumDeserializer;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.om.util.NonTaggedFormatUtil;
import edu.uci.ics.asterix.runtime.pointables.base.IVisitablePointable;
import edu.uci.ics.asterix.runtime.pointables.visitor.IVisitablePointableVisitor;
import edu.uci.ics.asterix.runtime.util.ResettableByteArrayOutputStream;
import edu.uci.ics.asterix.runtime.util.container.IElementFactory;
import edu.uci.ics.hyracks.api.dataflow.value.INullWriter;

public class ARecordPointable extends AbstractVisitablePointable {

    /**
     * DO NOT allow to create ARecordPointable object arbitrarily, force to use
     * object pool based allocator, in order to have object reuse
     */
    static IElementFactory<IVisitablePointable, IAType> FACTORY = new IElementFactory<IVisitablePointable, IAType>() {
        public IVisitablePointable createElement(IAType type) {
            return new ARecordPointable((ARecordType) type);
        }
    };

    // access results: field names, field types, and field values
    private List<IVisitablePointable> fieldNames = new ArrayList<IVisitablePointable>();
    private List<IVisitablePointable> fieldTypeTags = new ArrayList<IVisitablePointable>();
    private List<IVisitablePointable> fieldValues = new ArrayList<IVisitablePointable>();

    // pointable allocator
    private PointableAllocator allocator = new PointableAllocator();

    private byte[] typeBuffer = new byte[32768];
    private ResettableByteArrayOutputStream typeBos = new ResettableByteArrayOutputStream();
    private DataOutputStream typeDos = new DataOutputStream(typeBos);

    private byte[] dataBuffer = new byte[32768];
    private ResettableByteArrayOutputStream dataBos = new ResettableByteArrayOutputStream();
    private DataOutputStream dataDos = new DataOutputStream(dataBos);

    private int closedPartTypeInfoSize = 0;
    private ARecordType inputRecType;

    private int numberOfSchemaFields;
    private int offsetArrayOffset;
    private int[] fieldOffsets;
    private ATypeTag typeTag;
    private IVisitablePointable nullReference = AFlatValuePointable.FACTORY.createElement(null);

    /**
     * private constructor, to prevent constructing it
     * 
     * @param inputType
     *            , the input type
     */
    private ARecordPointable(ARecordType inputType) {
        this.inputRecType = inputType;
        IAType[] fieldTypes = inputType.getFieldTypes();
        String[] fieldNameStrs = inputType.getFieldNames();
        numberOfSchemaFields = fieldTypes.length;

        // initialize the buffer for closed parts(fieldName bytes+ type bytes) +
        // constant(null bytes)
        typeBos.setByteArray(typeBuffer, 0);
        try {
            for (int i = 0; i < numberOfSchemaFields; i++) {
                ATypeTag ftypeTag = fieldTypes[i].getTypeTag();

                if (fieldTypes[i].getTypeTag() == ATypeTag.UNION
                        && NonTaggedFormatUtil.isOptionalField((AUnionType) fieldTypes[i]))
                    // optional field: add the embedded non-null type tag
                    ftypeTag = ((AUnionType) fieldTypes[i]).getUnionList()
                            .get(NonTaggedFormatUtil.OPTIONAL_TYPE_INDEX_IN_UNION_LIST).getTypeTag();

                // add type tag Reference
                int tagStart = typeBos.size();
                typeDos.writeByte(ftypeTag.serialize());
                int tagEnd = typeBos.size();
                IVisitablePointable typeTagReference = AFlatValuePointable.FACTORY.createElement(null);
                typeTagReference.set(typeBuffer, tagStart, tagEnd - tagStart);
                fieldTypeTags.add(typeTagReference);

                // add type name Reference (including a astring type tag)
                int nameStart = typeBos.size();
                typeDos.writeByte(ATypeTag.STRING.serialize());
                typeDos.writeUTF(fieldNameStrs[i]);
                int nameEnd = typeBos.size();
                IVisitablePointable typeNameReference = AFlatValuePointable.FACTORY.createElement(null);
                typeNameReference.set(typeBuffer, nameStart, nameEnd - nameStart);
                fieldNames.add(typeNameReference);
            }

            // initialize a constant: null value bytes reference
            int nullFieldStart = typeBos.size();
            INullWriter nullWriter = AqlNullWriterFactory.INSTANCE.createNullWriter();
            nullWriter.writeNull(typeDos);
            int nullFieldEnd = typeBos.size();
            nullReference.set(typeBuffer, nullFieldStart, nullFieldEnd - nullFieldStart);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        closedPartTypeInfoSize = typeBos.size();
        fieldOffsets = new int[numberOfSchemaFields];
    }

    private void reset() {
        typeBos.setByteArray(typeBuffer, closedPartTypeInfoSize);
        dataBos.setByteArray(dataBuffer, 0);
        // reset the allocator
        allocator.reset();

        // clean up the returned containers
        for (int i = fieldNames.size() - 1; i >= numberOfSchemaFields; i--)
            fieldNames.remove(i);
        for (int i = fieldTypeTags.size() - 1; i >= numberOfSchemaFields; i--)
            fieldTypeTags.remove(i);
        fieldValues.clear();
    }

    @Override
    public void set(byte[] b, int start, int len) {
        // clear the previous states
        reset();
        super.set(b, start, len);

        boolean isExpanded = false;
        int openPartOffset = 0;
        int s = start;
        int recordOffset = s;
        if (inputRecType == null) {
            openPartOffset = s + AInt32SerializerDeserializer.getInt(b, s + 6);
            s += 8;
            isExpanded = true;
        } else {
            if (inputRecType.isOpen()) {
                isExpanded = b[s + 5] == 1 ? true : false;
                if (isExpanded) {
                    openPartOffset = s + AInt32SerializerDeserializer.getInt(b, s + 6);
                    s += 10;
                } else
                    s += 6;
            } else
                s += 5;
        }
        try {
            if (numberOfSchemaFields > 0) {
                s += 4;
                int nullBitMapOffset = 0;
                boolean hasNullableFields = NonTaggedFormatUtil.hasNullableField(inputRecType);
                if (hasNullableFields) {
                    nullBitMapOffset = s;
                    offsetArrayOffset = s
                            + (this.numberOfSchemaFields % 8 == 0 ? numberOfSchemaFields / 8
                                    : numberOfSchemaFields / 8 + 1);
                } else {
                    offsetArrayOffset = s;
                }
                for (int i = 0; i < numberOfSchemaFields; i++) {
                    fieldOffsets[i] = AInt32SerializerDeserializer.getInt(b, offsetArrayOffset) + recordOffset;
                    offsetArrayOffset += 4;
                }
                for (int fieldNumber = 0; fieldNumber < numberOfSchemaFields; fieldNumber++) {
                    if (hasNullableFields) {
                        byte b1 = b[nullBitMapOffset + fieldNumber / 8];
                        int p = 1 << (7 - (fieldNumber % 8));
                        if ((b1 & p) == 0) {
                            // set null value (including type tag inside)
                            fieldValues.add(nullReference);
                            continue;
                        }
                    }
                    IAType[] fieldTypes = inputRecType.getFieldTypes();
                    int fieldValueLength = 0;

                    IAType fieldType = fieldTypes[fieldNumber];
                    if (fieldTypes[fieldNumber].getTypeTag() == ATypeTag.UNION) {
                        if (NonTaggedFormatUtil.isOptionalField((AUnionType) fieldTypes[fieldNumber])) {
                            fieldType = ((AUnionType) fieldTypes[fieldNumber]).getUnionList().get(
                                    NonTaggedFormatUtil.OPTIONAL_TYPE_INDEX_IN_UNION_LIST);
                            typeTag = fieldType.getTypeTag();
                            fieldValueLength = NonTaggedFormatUtil.getFieldValueLength(b, fieldOffsets[fieldNumber],
                                    typeTag, false);
                        }
                    } else {
                        typeTag = fieldTypes[fieldNumber].getTypeTag();
                        fieldValueLength = NonTaggedFormatUtil.getFieldValueLength(b, fieldOffsets[fieldNumber],
                                typeTag, false);
                    }
                    // set field value (including the type tag)
                    int fstart = dataBos.size();
                    dataDos.writeByte(typeTag.serialize());
                    dataDos.write(b, fieldOffsets[fieldNumber], fieldValueLength);
                    int fend = dataBos.size();
                    IVisitablePointable fieldValue = allocator.allocateFieldValue(fieldType);
                    fieldValue.set(dataBuffer, fstart, fend - fstart);
                    fieldValues.add(fieldValue);
                }
            }
            if (isExpanded) {
                int numberOfOpenFields = AInt32SerializerDeserializer.getInt(b, openPartOffset);
                int fieldOffset = openPartOffset + 4 + (8 * numberOfOpenFields);
                for (int i = 0; i < numberOfOpenFields; i++) {
                    // set the field name (including a type tag, which is
                    // astring)
                    int fieldValueLength = NonTaggedFormatUtil.getFieldValueLength(b, fieldOffset, ATypeTag.STRING,
                            false);
                    int fnstart = dataBos.size();
                    dataDos.writeByte(ATypeTag.STRING.serialize());
                    dataDos.write(b, fieldOffset, fieldValueLength);
                    int fnend = dataBos.size();
                    IVisitablePointable fieldName = allocator.allocateEmpty();
                    fieldName.set(dataBuffer, fnstart, fnend - fnstart);
                    fieldNames.add(fieldName);
                    fieldOffset += fieldValueLength;

                    // set the field type tag
                    IVisitablePointable fieldTypeTag = allocator.allocateEmpty();
                    fieldTypeTag.set(b, fieldOffset, 1);
                    fieldTypeTags.add(fieldTypeTag);
                    typeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(b[fieldOffset]);

                    // set the field value (already including type tag)
                    fieldValueLength = NonTaggedFormatUtil.getFieldValueLength(b, fieldOffset, typeTag, true) + 1;

                    // allocate
                    IVisitablePointable fieldValueAccessor = allocator.allocateFieldValue(typeTag);
                    fieldValueAccessor.set(b, fieldOffset, fieldValueLength);
                    fieldValues.add(fieldValueAccessor);
                    fieldOffset += fieldValueLength;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<IVisitablePointable> getFieldNames() {
        return fieldNames;
    }

    public List<IVisitablePointable> getFieldTypeTags() {
        return fieldTypeTags;
    }

    public List<IVisitablePointable> getFieldValues() {
        return fieldValues;
    }

    @Override
    public <R, T> R accept(IVisitablePointableVisitor<R, T> vistor, T tag) throws AsterixException {
        return vistor.visit(this, tag);
    }

}