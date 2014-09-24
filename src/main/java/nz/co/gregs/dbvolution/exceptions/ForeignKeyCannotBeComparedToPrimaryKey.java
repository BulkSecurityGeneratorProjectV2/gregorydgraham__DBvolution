/*
 * Copyright 2014 Gregory Graham.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nz.co.gregs.dbvolution.exceptions;

import nz.co.gregs.dbvolution.internal.properties.PropertyWrapper;
import nz.co.gregs.dbvolution.query.RowDefinition;

/**
 *
 * @author Gregory Graham
 */
public class ForeignKeyCannotBeComparedToPrimaryKey extends DBRuntimeException {
	private static final long serialVersionUID = 1L;

	public ForeignKeyCannotBeComparedToPrimaryKey(Exception ex, RowDefinition source, PropertyWrapper sourceFK, RowDefinition target, PropertyWrapper targetPK) {
		super("Unable To Construct An Expression Representing The Foreign Key Relationship From "
				+source.getClass().getSimpleName()+":"+sourceFK.javaName()
				+" To "+target.getClass().getSimpleName()+":"+targetPK.javaName()+": Check that the 2 fields have similar and comparable datatypes or remove the @DBForeignKey annotation", 
				ex);
	}
	
}
