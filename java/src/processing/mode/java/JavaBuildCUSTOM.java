/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
Part of the Processing project - http://processing.org

Copyright (c) 2012-19 The Processing Foundation
Copyright (c) 2004-12 Ben Fry and Casey Reas
Copyright (c) 2001-04 Massachusetts Institute of Technology

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License version 2
as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software Foundation,
Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.mode.java;

import java.io.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;

import processing.app.Base;
import processing.app.Language;
import processing.app.Library;
import processing.app.Messages;
import processing.app.Mode;
import processing.app.Platform;
import processing.app.Preferences;
import processing.app.Sketch;
import processing.app.SketchCode;
import processing.app.SketchException;
import processing.app.Util;
import processing.app.exec.ProcessHelper;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.data.StringList;
import processing.data.XML;
import processing.mode.java.pdex.SourceUtils;
import processing.mode.java.preproc.PdePreprocessor;
import processing.mode.java.preproc.PreprocessorResult;
import processing.mode.java.preproc.SurfaceInfo;

public class JavaBuildCUSTOM {
	public static final String PACKAGE_REGEX = "(?:^|\\s|;)package\\s+(\\S+)\\;";

	protected Sketch sketch;
	protected Mode mode;

	// what happens in the build, stays in the build.
	// (which is to say that everything below this line, stays within this class)

	protected File srcFolder;
	protected File binFolder;
	private boolean foundMain = false;
	private String classPath;
	protected String sketchClassName;

	/**
	 * This will include the code folder, any library folders, etc. that might
	 * contain native libraries that need to be picked up with java.library.path.
	 * This is *not* the "Processing" libraries path, this is the Java libraries
	 * path, as in java.library.path=BlahBlah, which identifies search paths for
	 * DLLs or JNILIBs. (It's Java's LD_LIBRARY_PATH, for you UNIX fans.) This is
	 * set by the preprocessor as it figures out where everything is.
	 */
	private String javaLibraryPath;

	/** List of library folders, as figured out during preprocessing. */
	private List<Library> importedLibraries;

	public JavaBuildCUSTOM() {
	}

	/**
	 * Preprocess and compile all the code for this sketch.
	 *
	 * In an advanced program, the returned class name could be different, which is
	 * why the className is set based on the return value. A compilation error will
	 * burp up a RunnerException.
	 *
	 * @return null if compilation failed, main class name if not
	 */
	public String build(Sketch sketch, Mode mode, File srcFolder, File binFolder, boolean sizeWarning,
			boolean useEntPreprocessor, boolean useEntCompiler) throws SketchException {

		this.sketch = sketch;
		this.mode = mode;

		this.srcFolder = srcFolder;
		this.binFolder = binFolder;

		String classNameFound = null;
		if (useEntPreprocessor) {
			// TODO: (CL) implement EntPreprocessor
		} else {
			classNameFound = preprocess(srcFolder, sizeWarning);
		}

		if (useEntCompiler) {
			if (CompilerCUSTOM.compileCUSTOM(this)) {
				sketchClassName = classNameFound;
				return classNameFound;
			}
		} else {
			if (CompilerCUSTOM.compile(this)) {
				sketchClassName = classNameFound;
				return classNameFound;
			}
		}
		return null;
	}

	public String getSketchClassName() {
		return sketchClassName;
	}

	/**
	 * Build all the code for this sketch.
	 *
	 * In an advanced program, the returned class name could be different, which is
	 * why the className is set based on the return value. A compilation error will
	 * burp up a RunnerException.
	 *
	 * Setting purty to 'true' will cause exception line numbers to be incorrect.
	 * Unless you know the code compiles, you should first run the preprocessor with
	 * purty set to false to make sure there are no errors, then once successful,
	 * re-export with purty set to true.
	 *
	 * @param buildPath Location to copy all the .java files
	 * @return null if compilation failed, main class name if not
	 */
	public String preprocess(File srcFolder, boolean sizeWarning) throws SketchException {
		return preprocess(srcFolder, null, new PdePreprocessor(sketch.getName()), sizeWarning);
	}

	/**
	 * @param srcFolder    location where the .java source files will be placed
	 * @param packageName  null, or the package name that should be used as default
	 * @param preprocessor the preprocessor object ready to do the work
	 * @return main PApplet class name found during preprocess, or null if error
	 * @throws SketchException
	 */
	public String preprocess(File srcFolder, String packageName, PdePreprocessor preprocessor, boolean sizeWarning)
			throws SketchException {
		// make sure the user isn't playing "hide the sketch folder"
		sketch.ensureExistence();

//    System.out.println("srcFolder is " + srcFolder);
		classPath = binFolder.getAbsolutePath();

		// figure out the contents of the code folder to see if there
		// are files that need to be added to the imports
		StringList codeFolderPackages = null;
		if (sketch.hasCodeFolder()) {
			File codeFolder = sketch.getCodeFolder();
			javaLibraryPath = codeFolder.getAbsolutePath();

			// get a list of .jar files in the "code" folder
			// (class files in subfolders should also be picked up)
			String codeFolderClassPath = Util.contentsToClassPath(codeFolder);
			// append the jar files in the code folder to the class path
			classPath += File.pathSeparator + codeFolderClassPath;
			// get list of packages found in those jars
			codeFolderPackages = Util.packageListFromClassPath(codeFolderClassPath);

		} else {
			javaLibraryPath = "";
		}

		// 1. concatenate all .pde files to the 'main' pde
		// store line number for starting point of each code bit

		StringBuilder bigCode = new StringBuilder();
		int bigCount = 0;
		for (SketchCode sc : sketch.getCode()) {
			if (sc.isExtension("pde")) {
				sc.setPreprocOffset(bigCount);
				bigCode.append(sc.getProgram());
				bigCode.append('\n');
				bigCount += sc.getLineCount();
			}
		}

		// initSketchSize() sets the internal sketchWidth/Height/Renderer vars
		// in the preprocessor. Those are used in preproc.write() so that they
		// can be used to add methods (settings() or sketchXxxx())
		// String[] sizeParts =
		SurfaceInfo sizeInfo = preprocessor.initSketchSize(sketch.getMainProgram(), sizeWarning);
		if (sizeInfo == null) {
			// An error occurred while trying to pull out the size, so exit here
			return null;
		}
		// System.out.format("size() is '%s'%n", info[0]);

		// Remove the entries being moved to settings(). They will be re-inserted
		// by writeFooter() when it emits the settings() method.
		// If the user already has a settings() method, don't mess with anything.
		// https://github.com/processing/processing/issues/4703
		if (!PdePreprocessor.hasSettingsMethod(bigCode.toString()) && sizeInfo != null && sizeInfo.hasSettings()) {
			for (String stmt : sizeInfo.getStatements()) {
				// Don't remove newlines (and while you're at it, just keep spaces)
				// https://github.com/processing/processing/issues/3654
				stmt = stmt.trim();
				int index = bigCode.indexOf(stmt);
				if (index != -1) {
					bigCode.delete(index, index + stmt.length());
				} else {
					// TODO remove once we hit final; but prevent an exception like in
					// https://github.com/processing/processing/issues/3531
					System.err.format("Error removing '%s' from the code.", stmt);
				}
			}
		}

		PreprocessorResult result;
		try {
			File outputFolder = (packageName == null) ? srcFolder : new File(srcFolder, packageName.replace('.', '/'));
			outputFolder.mkdirs();
			final File java = new File(outputFolder, sketch.getName() + ".java");
			try {
				final PrintWriter writer = PApplet.createWriter(java);
				try {
					result = preprocessor.write(writer, bigCode.toString(), codeFolderPackages);
				} finally {
					writer.close();
				}
			} catch (RuntimeException re) {
				re.printStackTrace();
				throw new SketchException("Could not write " + java.getAbsolutePath());
			}
		} catch (antlr.RecognitionException re) {
			// re also returns a column that we're not bothering with for now
			// first assume that it's the main file
//      int errorFile = 0;
			int errorLine = re.getLine() - 1;

			// then search through for anyone else whose preprocName is null,
			// since they've also been combined into the main pde.
			int errorFile = findErrorFile(errorLine);
			errorLine -= sketch.getCode(errorFile).getPreprocOffset();

			String msg = re.getMessage();

			if (msg.contains("expecting RCURLY") || msg.contains("expecting LCURLY")) {
				for (int i = 0; i < sketch.getCodeCount(); i++) {
					SketchCode sc = sketch.getCode(i);
					if (sc.isExtension("pde")) {
						String s = sc.getProgram();
						int[] braceTest = SourceUtils.checkForMissingBraces(
								SourceUtils.scrubCommentsAndStrings(s) + "\n", 0, s.length() + 1);
						if (braceTest[0] == 0)
							continue;

						// Completely ignoring the errorFile/errorLine given since it's
						// likely to be the wrong tab. For the same reason, I'm not showing
						// the result of PApplet.match(msg, "found ('.*')") on missing
						// LCURLY.
						throw new SketchException(
								braceTest[0] > 0 ? "Found an extra { character without a } to match it."
										: "Found an extra } character without a { to match it.",
								i, braceTest[1], braceTest[2], false);
					}
				}
				// If we're still here, there's the right brackets, just not in the
				// right place. Passing on the original error.
				throw new SketchException(msg.replace("LCURLY", "{").replace("RCURLY", "}"), errorFile, errorLine,
						re.getColumn(), false);
			}

			if (msg.indexOf("expecting RBRACK") != -1) {
				System.err.println(msg);
				throw new SketchException("Syntax error, " + "maybe a missing ] character?", errorFile, errorLine,
						re.getColumn(), false);
			}

			if (msg.indexOf("expecting SEMI") != -1) {
				System.err.println(msg);
				throw new SketchException("Syntax error, " + "maybe a missing semicolon?", errorFile, errorLine,
						re.getColumn(), false);
			}

			if (msg.indexOf("expecting RPAREN") != -1) {
				System.err.println(msg);
				throw new SketchException("Syntax error, " + "maybe a missing right parenthesis?", errorFile, errorLine,
						re.getColumn(), false);
			}

			if (msg.indexOf("preproc.web_colors") != -1) {
				throw new SketchException("A web color (such as #ffcc00) " + "must be six digits.", errorFile,
						errorLine, re.getColumn(), false);
			}

			// System.out.println("msg is " + msg);
			throw new SketchException(msg, errorFile, errorLine, re.getColumn(), false);

		} catch (antlr.TokenStreamRecognitionException tsre) {
			// while this seems to store line and column internally,
			// there doesn't seem to be a method to grab it..
			// so instead it's done using a regexp

//      System.err.println("and then she tells me " + tsre.toString());
			// TODO not tested since removing ORO matcher.. ^ could be a problem
			String locationRegex = "^line (\\d+):(\\d+):\\s";
			String message = tsre.getMessage();
			String[] m;

			if (null != (m = PApplet.match(tsre.toString(), "unexpected char: (.*)"))) {
				char c = 0;
				if (m[1].startsWith("0x")) { // Hex
					c = (char) PApplet.unhex(m[1].substring(2));
				} else if (m[1].length() == 3) { // Quoted
					c = m[1].charAt(1);
				} else if (m[1].length() == 1) { // Alone
					c = m[1].charAt(0);
				}
				if (c == '\u201C' || c == '\u201D' || // “”
						c == '\u2018' || c == '\u2019') { // ‘’
					message = Language.interpolate("editor.status.bad_curly_quote", c);
				} else if (c != 0) {
					message = "Not expecting symbol " + m[1] + ", which is " + Character.getName(c) + ".";
				}
			}

			String[] matches = PApplet.match(tsre.toString(), locationRegex);
			if (matches != null) {
				int errorLine = Integer.parseInt(matches[1]) - 1;
				int errorColumn = Integer.parseInt(matches[2]);

				int errorFile = 0;
				for (int i = 1; i < sketch.getCodeCount(); i++) {
					SketchCode sc = sketch.getCode(i);
					if (sc.isExtension("pde") && (sc.getPreprocOffset() < errorLine)) {
						errorFile = i;
					}
				}
				errorLine -= sketch.getCode(errorFile).getPreprocOffset();

				throw new SketchException(message, errorFile, errorLine, errorColumn);

			} else {
				// this is bad, defaults to the main class.. hrm.
				String msg = tsre.toString();
				throw new SketchException(msg, 0, -1, -1);
			}

		} catch (SketchException pe) {
			// RunnerExceptions are caught here and re-thrown, so that they don't
			// get lost in the more general "Exception" handler below.
			throw pe;

		} catch (Exception ex) {
			// TODO better method for handling this?
			System.err.println("Uncaught exception type:" + ex.getClass());
			ex.printStackTrace();
			throw new SketchException(ex.toString());
		}

		// grab the imports from the code just preprocessed

		importedLibraries = new ArrayList<>();
		Library core = mode.getCoreLibrary();
		if (core != null) {
			importedLibraries.add(core);
			classPath += core.getClassPath();
			javaLibraryPath += File.pathSeparator + core.getNativePath();
		}

		for (String item : result.extraImports) {
			// remove things up to the last dot
			int dot = item.lastIndexOf('.');
			// http://dev.processing.org/bugs/show_bug.cgi?id=1145
			String entry = (dot == -1) ? item : item.substring(0, dot);

			if (item.startsWith("static ")) {
				// import static - https://github.com/processing/processing/issues/8
				int dot2 = item.lastIndexOf('.');
				entry = entry.substring(7, (dot2 == -1) ? entry.length() : dot2);
			}

			Library library = mode.getLibrary(entry);

			if (library != null) {
				if (!importedLibraries.contains(library)) {
					importedLibraries.add(library);
					classPath += library.getClassPath();
					javaLibraryPath += File.pathSeparator + library.getNativePath();
				}
			} else {
				boolean found = false;
				// If someone insists on unnecessarily repeating the code folder
				// import, don't show an error for it.
				if (codeFolderPackages != null) {
					String itemPkg = entry;
					for (String pkg : codeFolderPackages) {
						if (pkg.equals(itemPkg)) {
							found = true;
							break;
						}
					}
				}
				if (ignorableImport(entry + '.')) {
					found = true;
				}
				if (!found) {
					System.err.println("No library found for " + entry);
				}
			}
		}

		// Finally, add the regular Java CLASSPATH. This contains everything
		// imported by the PDE itself (core.jar, pde.jar, quaqua.jar) which may
		// in fact be more of a problem.
		String javaClassPath = System.getProperty("java.class.path");
		// Remove quotes if any.. A messy (and frequent) Windows problem
		if (javaClassPath.startsWith("\"") && javaClassPath.endsWith("\"")) {
			javaClassPath = javaClassPath.substring(1, javaClassPath.length() - 1);
		}
		classPath += File.pathSeparator + javaClassPath;

		// But make sure that there isn't anything in there that's missing,
		// otherwise ECJ will complain and die. For instance, Java 1.7 (or maybe
		// it's appbundler?) adds Java/Classes to the path, which kills us.
		// String[] classPieces = PApplet.split(classPath, File.pathSeparator);
		// Nah, nevermind... we'll just create the @!#$! folder until they fix it.

		// 3. then loop over the code[] and save each .java file

		for (SketchCode sc : sketch.getCode()) {
			if (sc.isExtension("java")) {
				// In most cases, no pre-processing services necessary for Java files.
				// Just write the the contents of 'program' to a .java file
				// into the build directory. However, if a default package is being
				// used (as in Android), and no package is specified in the source,
				// then we need to move this code to the same package as the sketch.
				// Otherwise, the class may not be found, or at a minimum, the default
				// access across the packages will mean that things behave incorrectly.
				// For instance, desktop code that uses a .java file with no packages,
				// will be fine with the default access, but since Android's PApplet
				// requires a package, code from that (default) package (such as the
				// PApplet itself) won't have access to methods/variables from the
				// package-less .java file (unless they're all marked public).
				String filename = sc.getFileName();
				try {
					String javaCode = sc.getProgram();
					String[] packageMatch = PApplet.match(javaCode, PACKAGE_REGEX);
					// if no package, and a default package is being used
					// (i.e. on Android) we'll have to add one

					if (packageMatch == null && packageName == null) {
						sc.copyTo(new File(srcFolder, filename));

					} else {
						if (packageMatch == null) {
							// use the default package name, since mixing with package-less code will break
							packageMatch = new String[] { "", packageName };
							// add the package name to the source before writing it
							javaCode = "package " + packageName + ";" + javaCode;
						}
						File packageFolder = new File(srcFolder, packageMatch[1].replace('.', File.separatorChar));
						packageFolder.mkdirs();
						Util.saveFile(javaCode, new File(packageFolder, filename));
					}

				} catch (IOException e) {
					e.printStackTrace();
					String msg = "Problem moving " + filename + " to the build folder";
					throw new SketchException(msg);
				}

			} else if (sc.isExtension("pde")) {
				// The compiler and runner will need this to have a proper offset
				sc.addPreprocOffset(result.headerOffset);
			}
		}
		foundMain = preprocessor.hasMethod("main");
		return result.className;
	}

	/**
	 * Returns true if this package isn't part of a library (it's a system import or
	 * something like that). Don't bother complaining about java.* or javax.*
	 * because it's probably in boot.class.path. But we're not checking against that
	 * path since it's enormous. Unfortunately we do still have to check for
	 * libraries that begin with a prefix like javax, since that includes the OpenGL
	 * library, even though we're just returning true here, hrm...
	 */
	protected boolean ignorableImport(String pkg) {
		if (pkg.startsWith("java."))
			return true;
		if (pkg.startsWith("javax."))
			return true;
		if (pkg.startsWith("javafx."))
			return true;

		if (pkg.startsWith("processing.core."))
			return true;
		if (pkg.startsWith("processing.data."))
			return true;
		if (pkg.startsWith("processing.event."))
			return true;
		if (pkg.startsWith("processing.opengl."))
			return true;

		return false;
	}

	protected int findErrorFile(int errorLine) {
		for (int i = sketch.getCodeCount() - 1; i > 0; i--) {
			SketchCode sc = sketch.getCode(i);
			if (sc.isExtension("pde") && (sc.getPreprocOffset() <= errorLine)) {
				// keep looping until the errorLine is past the offset
				return i;
			}
		}
		return 0; // i give up
	}

	/**
	 * Path to the folder that will contain processed .java source files. Not the
	 * location for .pde files, since that can be obtained from the sketch.
	 */
	public File getSrcFolder() {
		return srcFolder;
	}

	public File getBinFolder() {
		return binFolder;
	}

	/**
	 * Absolute path to the sketch folder. Used to set the working directry of the
	 * sketch when running, i.e. so that saveFrame() goes to the right location when
	 * running from the PDE, instead of the same folder as the Processing.exe or the
	 * root of the user's home dir.
	 */
	public String getSketchPath() {
		return sketch.getFolder().getAbsolutePath();
	}

	/** Class path determined during build. */
	public String getClassPath() {
		return classPath;
	}

	/**
	 * Return the java.library.path for this sketch (for all the native DLLs etc).
	 */
	public String getJavaLibraryPath() {
		return javaLibraryPath;
	}

	/**
	 * Whether the preprocessor found a main() method. If main() is found, then it
	 * will be used to launch the sketch instead of PApplet.main().
	 */
	public boolean getFoundMain() {
		return foundMain;
	}

	/**
	 * Get the list of imported libraries. Used by external tools like Android mode.
	 * 
	 * @return list of library folders connected to this sketch.
	 */
	public List<Library> getImportedLibraries() {
		return importedLibraries;
	}

	/**
	 * Map an error from a set of processed .java files back to its location in the
	 * actual sketch.
	 * 
	 * @param message  The error message.
	 * @param filename The .java file where the exception was found.
	 * @param line     Line number of the .java file for the exception (0-indexed!)
	 * @return A RunnerException to be sent to the editor, or null if it wasn't
	 *         possible to place the exception to the sketch code.
	 */
	public SketchException placeException(String message, String dotJavaFilename, int dotJavaLine) {
		int codeIndex = 0; // -1;
		int codeLine = -1;

//    System.out.println("placing " + dotJavaFilename + " " + dotJavaLine);
//    System.out.println("code count is " + getCodeCount());

		// first check to see if it's a .java file
		for (int i = 0; i < sketch.getCodeCount(); i++) {
			SketchCode code = sketch.getCode(i);
			if (code.isExtension("java")) {
				if (dotJavaFilename.equals(code.getFileName())) {
					codeIndex = i;
					codeLine = dotJavaLine;
					return new SketchException(message, codeIndex, codeLine);
				}
			}
		}

		// If not the preprocessed file at this point, then need to get out
		if (!dotJavaFilename.equals(sketch.getName() + ".java")) {
			return null;
		}

		// if it's not a .java file, codeIndex will still be 0
		// this section searches through the list of .pde files
		codeIndex = 0;
		for (int i = 0; i < sketch.getCodeCount(); i++) {
			SketchCode code = sketch.getCode(i);

			if (code.isExtension("pde")) {
//        System.out.println("preproc offset is " + code.getPreprocOffset());
//        System.out.println("looking for line " + dotJavaLine);
				if (code.getPreprocOffset() <= dotJavaLine) {
					codeIndex = i;
//          System.out.println("i'm thinkin file " + i);
					codeLine = dotJavaLine - code.getPreprocOffset();
				}
			}
		}
		// could not find a proper line number, so deal with this differently.
		// but if it was in fact the .java file we're looking for, though,
		// send the error message through.
		// this is necessary because 'import' statements will be at a line
		// that has a lower number than the preproc offset, for instance.
//    if (codeLine == -1 && !dotJavaFilename.equals(name + ".java")) {
//      return null;
//    }
//    return new SketchException(message, codeIndex, codeLine);
		return new SketchException(message, codeIndex, codeLine, -1, false); // changed for 0194 for compile errors,
																				// but...
	}

	static Boolean xcodeInstalled;

	static protected boolean isXcodeInstalled() {
		if (xcodeInstalled == null) {
			// http://stackoverflow.com/questions/15371925
			Process p = PApplet.launch("xcode-select", "-p");
			int result = -1;
			try {
				result = p.waitFor();
			} catch (InterruptedException e) {
			}
			// returns 0 if installed, 2 if not (-1 if exception)
			xcodeInstalled = (result == 0);
		}
		return xcodeInstalled;
	}

	/**
	 * Run the launch4j build.xml file through ant to create the exe. Most of this
	 * code was lifted from Android mode.
	 */
	protected boolean buildWindowsLauncher(File buildFile, String target) {
		Project p = new Project();
		String path = buildFile.getAbsolutePath().replace('\\', '/');
		p.setUserProperty("ant.file", path);

		// deals with a problem where javac error messages weren't coming through
		p.setUserProperty("build.compiler", "extJavac");

		// too chatty
		/*
		 * // try to spew something useful to the console final DefaultLogger
		 * consoleLogger = new DefaultLogger();
		 * consoleLogger.setErrorPrintStream(System.err);
		 * consoleLogger.setOutputPrintStream(System.out); // WARN, INFO, VERBOSE, DEBUG
		 * consoleLogger.setMessageOutputLevel(Project.MSG_ERR);
		 * p.addBuildListener(consoleLogger);
		 */

		DefaultLogger errorLogger = new DefaultLogger();
		ByteArrayOutputStream errb = new ByteArrayOutputStream();
		PrintStream errp = new PrintStream(errb);
		errorLogger.setErrorPrintStream(errp);
		ByteArrayOutputStream outb = new ByteArrayOutputStream();
		PrintStream outp = new PrintStream(outb);
		errorLogger.setOutputPrintStream(outp);
		errorLogger.setMessageOutputLevel(Project.MSG_INFO);
		p.addBuildListener(errorLogger);

		try {
			p.fireBuildStarted();
			p.init();
			final ProjectHelper helper = ProjectHelper.getProjectHelper();
			p.addReference("ant.projectHelper", helper);
			helper.parse(p, buildFile);
			p.executeTarget(target);
			return true;

		} catch (final BuildException e) {
			// Send a "build finished" event to the build listeners for this project.
			p.fireBuildFinished(e);

			String out = new String(outb.toByteArray());
			String err = new String(errb.toByteArray());
			System.out.println(out);
			System.err.println(err);
		}
		return false;
	}

	protected void addManifest(ZipOutputStream zos) throws IOException {
		ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
		zos.putNextEntry(entry);

		String contents = "Manifest-Version: 1.0\n" + "Created-By: Processing " + Base.getVersionName() + "\n"
				+ "Main-Class: " + sketch.getName() + "\n"; // TODO not package friendly
		zos.write(contents.getBytes());
		zos.closeEntry();
	}

	protected void addClasses(ZipOutputStream zos, File dir) throws IOException {
		String path = dir.getAbsolutePath();
		if (!path.endsWith("/") && !path.endsWith("\\")) {
			path += '/';
		}
//    System.out.println("path is " + path);
		addClasses(zos, dir, path);
	}

	protected void addClasses(ZipOutputStream zos, File dir, String rootPath) throws IOException {
		File files[] = dir.listFiles(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return (name.charAt(0) != '.');
			}
		});
		for (File sub : files) {
			String relativePath = sub.getAbsolutePath().substring(rootPath.length());
//      System.out.println("relative path is " + relativePath);

			if (sub.isDirectory()) {
				addClasses(zos, sub, rootPath);

			} else if (sub.getName().endsWith(".class")) {
//        System.out.println("  adding item " + relativePath);
				ZipEntry entry = new ZipEntry(relativePath);
				zos.putNextEntry(entry);
				// zos.write(Base.loadBytesRaw(sub));
				PApplet.saveStream(zos, new FileInputStream(sub));
				zos.closeEntry();
			}
		}
	}

	protected void addDataFolder(ZipOutputStream zos) throws IOException {
		if (sketch.hasDataFolder()) {
			String[] dataFiles = Util.listFiles(sketch.getDataFolder(), false);
			int offset = sketch.getFolder().getAbsolutePath().length() + 1;
			for (String path : dataFiles) {
				if (Platform.isWindows()) {
					path = path.replace('\\', '/');
				}
				// File dataFile = new File(dataFiles[i]);
				File dataFile = new File(path);
				if (!dataFile.isDirectory()) {
					// don't export hidden files
					// skipping dot prefix removes all: . .. .DS_Store
					if (dataFile.getName().charAt(0) != '.') {
						ZipEntry entry = new ZipEntry(path.substring(offset));
						zos.putNextEntry(entry);
						// zos.write(Base.loadBytesRaw(dataFile));
						PApplet.saveStream(zos, new FileInputStream(dataFile));
						zos.closeEntry();
					}
				}
			}
		}
	}

}
