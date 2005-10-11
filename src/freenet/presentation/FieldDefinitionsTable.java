/*
 * Created on Jan 13, 2004
 *
 */
package freenet.presentation;

import java.lang.reflect.Method;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;

import freenet.Core;
import freenet.crypt.Digest;
import freenet.crypt.SHA1Factory;
import freenet.support.HexUtil;
import freenet.support.Logger;

/**
 * @author Iakin
 * A class for defining Message FieldSet fields and resolving their respective datatypes 
 */
public class FieldDefinitionsTable
	{
		final MessageFieldsInfo[] messageFieldsInfo;
		final Hashtable inverseMessageFieldsInfo = new Hashtable(); //From String to InverseMessageFieldsInfo (messagename to id+fieldnameid's)
		
		FieldDefinitionsTable(MessageFieldsInfo[] messageFieldsInfo){
			if(messageFieldsInfo.length > 127) // table of message names; *must* be less than 128 entries long
				throw new IllegalArgumentException("To many message names ("+messageFieldsInfo.length+">127)");
			this.messageFieldsInfo = messageFieldsInfo;
			for(int i =0;i<this.messageFieldsInfo.length;i++)
				inverseMessageFieldsInfo.put(this.messageFieldsInfo[i].messageName,new InverseMessageFieldsInfo(i,this.messageFieldsInfo[i]));
		}
		
		//Returns a digest identifying the defined structure 
		String getDigest(){
			Digest d = new SHA1Factory().getInstance();
			for(int i = 0;i<messageFieldsInfo.length;i++)
				d.update(messageFieldsInfo[i].getDigest());
			return HexUtil.bytesToHex(d.digest());
		}
		
		//Returns the return datatype of the first 0-argument method, up the inheritance tree, matching the supplied name
		private static Class findGetterDatatype(String getterMethodName, Class c) throws NoSuchMethodException
		{
			return findGetter(getterMethodName,c).getReturnType();
		}
		
		//Returns the first 0-argument method, up the inheritance tree, matching the supplied name
		private static Method findGetter(String getterMethodName, Class c) throws NoSuchMethodException
		{
			return c.getMethod(getterMethodName, new Class[0]);
		}
		
		//A pair of field name and field datatype
		public static class FieldNameAndFieldType{
				public final String fieldName;
				public final Class fieldType;
				FieldNameAndFieldType(String fieldName,Class fieldType){
					this.fieldName = fieldName;
					this.fieldType = fieldType;
				}
				
				//Returns a digest identifying the defined structure
				byte[] getDigest(){
					Digest d = new SHA1Factory().getInstance();
					d.update(fieldName.getBytes());
					d.update(fieldType.toString().getBytes());
					return d.digest();
				}
		}
		public static class FieldIDAndFieldType
		{
			public int fieldID;
			public final Class fieldType;
			FieldIDAndFieldType(int fieldID, Class fieldType){
				this.fieldID = fieldID;
				this.fieldType = fieldType;
			}

		}
		
		public static class FieldDefinition{
				public final String fieldName;
				private final String alternateGetterName;
			
				//The datatype of the field
				public Class fieldType; //TODO: Ideally I'd like to make this field final also.... 
			
				//Will be != null if this field actually renders to a fieldset
				public final FieldDefinition[] subFieldDefinition;
			
				//Create a new FieldDefiniton with no sub-fieldset and automatic fieldType resolving
				FieldDefinition(String fieldName){
					this.fieldName = fieldName;
					this.subFieldDefinition = null;
					this.alternateGetterName = null;
				}
				//	Create a new FieldDefiniton with no sub-fieldset and automatic fieldType resolving using an alternate getter
				FieldDefinition(String fieldName,String alternateGetterName){
					this.fieldName = fieldName;
					this.subFieldDefinition = null;
					this.alternateGetterName = alternateGetterName;
				}
				FieldDefinition(String fieldName,String alternateGetterName,FieldDefinition[] subFieldDefinition){
					this.fieldName = fieldName;
					this.subFieldDefinition = subFieldDefinition;
					this.alternateGetterName = alternateGetterName;
				}
			
				//Create a new FieldDefiniton with no sub-fieldset and manual fieldType specification
				//DANGER! If the fieldType specification is wrong we might get much trouble later on
				//Use one of the other constructors if possible
				FieldDefinition(String fieldName,Class overrideFieldDatatype){
					this.fieldName = fieldName;
					this.fieldType = overrideFieldDatatype;
					this.subFieldDefinition = null;
					this.alternateGetterName = null;
				}
				FieldDefinition(Class c, String fieldName){
					this.fieldName = fieldName;
					this.subFieldDefinition = null;
					this.alternateGetterName = null;
					resolveDatatypeFromParentClass(c);
				}
				FieldDefinition(String fieldName,FieldDefinition[] subFieldDefinition){
					this.fieldName = fieldName;
					this.subFieldDefinition = subFieldDefinition;
					this.alternateGetterName = null;
				}
				
				void resolveDatatypeFromParentClass(Class c/*a messageClass*/) throws IllegalArgumentException{
					if(this.fieldType == null)
					{	
						String ft = alternateGetterName==null?fieldName:alternateGetterName;
						try {
							this.fieldType = findGetterDatatype("get"+ft, c); //Getter of any type
						} catch (NoSuchMethodException e) {
							try{
								this.fieldType = findGetterDatatype("is"+ft, c); //Special boolean-returning getter 
							} catch (NoSuchMethodException e2) {
								throw new IllegalArgumentException("Unable to resolve type information for fieldName '"+fieldName+"'");
							}
						}
					}
					if(subFieldDefinition != null)
					{	
						for(int i =0;i<this.subFieldDefinition.length;i++){ //Initialize all our subclasses
							if(this.subFieldDefinition[i] != null)//This situation might happen, for instance if we are sharing the FieldDefinition[] with someone else or if some evil bastard passes us some nulls...  
							{	
								try {
									this.subFieldDefinition[i].resolveDatatypeFromParentClass(this.fieldType);
								}catch(IllegalArgumentException e){
									Core.logger.log(this,"Failed to initialize subfield, disabling subfield '"+this.subFieldDefinition[i].fieldName+"'", Logger.ERROR);
									this.subFieldDefinition[i] = null; //Forget all about it
								}
							}
						}
					}
				}
				
				//Returns a List containing FieldNameAndFieldType's whose fieldName's are set to the complete field name (xxx.yyy.zzz)
				List getFieldListRecursive(){
					return getFieldListRecursive("");
				}
				//Returns a List containing FieldNameAndFieldType's whose fieldName's are set to the complete field name and prefixed by 'pre' (preXXXX.yyyy)
				private List getFieldListRecursive(String pre){
					List l = new LinkedList();
					l.add(new FieldNameAndFieldType(pre+this.fieldName,this.fieldType));
					if(this.subFieldDefinition != null)
						for(int i =0;i<this.subFieldDefinition.length;i++) //Initialize all our subclasses
							if(this.subFieldDefinition[i] != null) //Skip any disabled subfieldsets
								l.addAll(this.subFieldDefinition[i].getFieldListRecursive(pre+this.fieldName+"."));
					return l;
				}
			}
		public static class InverseMessageFieldsInfo{
			public int messageTypeID;
			public Hashtable fieldCodes = new Hashtable(); //From String to Integer (FieldName to FieldID)
			public InverseMessageFieldsInfo(int messageTypeID, MessageFieldsInfo m){
				this.messageTypeID = messageTypeID;
				for(int i =0;i<m.ctable.length;i++)
					fieldCodes.put(m.ctable[i].fieldName,new FieldIDAndFieldType(i,m.ctable[i].fieldType));
			}
		}
			
		
		public static class MessageFieldsInfo{

				final String messageName;
				private final FieldDefinition[] messageFields;
				FieldNameAndFieldType[] ctable;
				Hashtable ctableInverse = new Hashtable();
				
				MessageFieldsInfo(Class messageClass,FieldDefinition[] messageFields) throws IllegalArgumentException, SecurityException, IllegalAccessException, NoSuchFieldException{
					this.messageName = (String)messageClass.getField("messageName").get(null);
					this.messageFields = messageFields;
					
					//Flatten the field substructure into a single array
					List l = new LinkedList();
					if(this.messageFields != null)
					{	
						for(int i =0;i<this.messageFields.length;i++) //Initialize all our fieldInfos
						{	
							try{
							this.messageFields[i].resolveDatatypeFromParentClass(messageClass);
							l.addAll(this.messageFields[i].getFieldListRecursive());
							}catch(IllegalArgumentException e){
								Core.logger.log(this,"Failed to initialize field, disabling field '"+this.messageFields[i].fieldName+"'", Logger.ERROR);
								this.messageFields[i] = null; //Forget all about it
							}
						}
					}
			
					this.ctable = new FieldNameAndFieldType[l.size()];
					l.toArray(this.ctable);
					for(int i =0;i<this.ctable.length;i++)
						ctableInverse.put(this.ctable[i].fieldName,this.ctable[i]);
				}
				
				//Returns a digest identifying the defined structure
				byte[] getDigest(){
					Digest d = new SHA1Factory().getInstance();
					d.update(messageName.getBytes());
					for(int i =0;i<this.ctable.length;i++) //Initialize all our verbs
						d.update(this.ctable[i].getDigest());
					return d.digest();
				}
			}
	}
