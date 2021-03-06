// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Tests for {@link TreeArtifactValue}. */
@RunWith(JUnit4.class)
public final class TreeArtifactValueTest {

  private final Scratch scratch = new Scratch();
  private final ArtifactRoot root = ArtifactRoot.asDerivedRoot(scratch.resolve("root"), "bin");

  @Test
  public void builderCreatesCorrectValue() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(parent, "child1");
    TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(parent, "child2");
    FileArtifactValue metadata1 = metadataWithId(1);
    FileArtifactValue metadata2 = metadataWithId(2);

    TreeArtifactValue tree =
        TreeArtifactValue.newBuilder(parent)
            .putChild(child1, metadata1)
            .putChild(child2, metadata2)
            .build();

    assertThat(tree.getChildren()).containsExactly(child1, child2);
    assertThat(tree.getChildValues()).containsExactly(child1, metadata1, child2, metadata2);
    assertThat(tree.getChildPaths())
        .containsExactly(child1.getParentRelativePath(), child2.getParentRelativePath());
    assertThat(tree.getDigest()).isNotNull();
    assertThat(tree.getMetadata().getDigest()).isEqualTo(tree.getDigest());
  }

  @Test
  public void empty() {
    TreeArtifactValue emptyTree = TreeArtifactValue.empty();

    assertThat(emptyTree.getChildren()).isEmpty();
    assertThat(emptyTree.getChildValues()).isEmpty();
    assertThat(emptyTree.getChildPaths()).isEmpty();
    assertThat(emptyTree.getDigest()).isNotNull();
    assertThat(emptyTree.getMetadata().getDigest()).isEqualTo(emptyTree.getDigest());
  }

  @Test
  public void canonicalEmptyInstance() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");

    TreeArtifactValue emptyTreeFromBuilder = TreeArtifactValue.newBuilder(parent).build();

    assertThat(emptyTreeFromBuilder).isSameInstanceAs(TreeArtifactValue.empty());
  }

  @Test
  public void cannotCreateBuilderForNonTreeArtifact() {
    SpecialArtifact notTreeArtifact =
        new SpecialArtifact(
            root,
            PathFragment.create("bin/not_tree"),
            ActionsTestUtil.NULL_ARTIFACT_OWNER,
            SpecialArtifactType.FILESET);

    assertThrows(
        IllegalArgumentException.class, () -> TreeArtifactValue.newBuilder(notTreeArtifact));
  }

  @Test
  public void cannotMixParentsWithinSingleBuilder() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact childOfAnotherParent =
        TreeFileArtifact.createTreeOutput(createTreeArtifact("bin/other_tree"), "child");

    TreeArtifactValue.Builder builderForParent = TreeArtifactValue.newBuilder(parent);

    assertThrows(
        IllegalArgumentException.class,
        () -> builderForParent.putChild(childOfAnotherParent, metadataWithId(1)));
  }

  @Test
  public void cannotAddOmittedChildToBuilder() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child = TreeFileArtifact.createTreeOutput(parent, "child");

    TreeArtifactValue.Builder builder = TreeArtifactValue.newBuilder(parent);

    assertThrows(
        IllegalArgumentException.class,
        () -> builder.putChild(child, FileArtifactValue.OMITTED_FILE_MARKER));
  }

  @Test
  public void orderIndependence() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(parent, "child1");
    TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(parent, "child2");
    FileArtifactValue metadata1 = metadataWithId(1);
    FileArtifactValue metadata2 = metadataWithId(2);

    TreeArtifactValue tree1 =
        TreeArtifactValue.newBuilder(parent)
            .putChild(child1, metadata1)
            .putChild(child2, metadata2)
            .build();
    TreeArtifactValue tree2 =
        TreeArtifactValue.newBuilder(parent)
            .putChild(child2, metadata2)
            .putChild(child1, metadata1)
            .build();

    assertThat(tree1).isEqualTo(tree2);
  }

  @Test
  public void nullDigests_equal() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child = TreeFileArtifact.createTreeOutput(parent, "child");
    FileArtifactValue metadataNoDigest = metadataWithIdNoDigest(1);

    TreeArtifactValue tree1 =
        TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest).build();
    TreeArtifactValue tree2 =
        TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest).build();

    assertThat(metadataNoDigest.getDigest()).isNull();
    assertThat(tree1.getDigest()).isNotNull();
    assertThat(tree2.getDigest()).isNotNull();
    assertThat(tree1).isEqualTo(tree2);
  }

  @Test
  public void nullDigests_notEqual() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child = TreeFileArtifact.createTreeOutput(parent, "child");
    FileArtifactValue metadataNoDigest1 = metadataWithIdNoDigest(1);
    FileArtifactValue metadataNoDigest2 = metadataWithIdNoDigest(2);

    TreeArtifactValue tree1 =
        TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest1).build();
    TreeArtifactValue tree2 =
        TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest2).build();

    assertThat(metadataNoDigest1.getDigest()).isNull();
    assertThat(metadataNoDigest2.getDigest()).isNull();
    assertThat(tree1.getDigest()).isNotNull();
    assertThat(tree2.getDigest()).isNotNull();
    assertThat(tree1.getDigest()).isNotEqualTo(tree2.getDigest());
  }

  @Test
  public void visitTree_visitsEachChild() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.file("tree/file1");
    scratch.file("tree/a/file2");
    scratch.file("tree/a/b/file3");
    scratch.resolve("tree/file_link").createSymbolicLink(PathFragment.create("file1"));
    scratch.resolve("tree/a/dir_link").createSymbolicLink(PathFragment.create("c"));
    scratch.resolve("tree/a/b/dangling_link").createSymbolicLink(PathFragment.create("?"));
    List<Pair<PathFragment, Dirent.Type>> children = new ArrayList<>();

    TreeArtifactValue.visitTree(treeDir, (child, type) -> children.add(Pair.of(child, type)));

    assertThat(children)
        .containsExactly(
            Pair.of(PathFragment.create("a"), Dirent.Type.DIRECTORY),
            Pair.of(PathFragment.create("a/b"), Dirent.Type.DIRECTORY),
            Pair.of(PathFragment.create("file1"), Dirent.Type.FILE),
            Pair.of(PathFragment.create("a/file2"), Dirent.Type.FILE),
            Pair.of(PathFragment.create("a/b/file3"), Dirent.Type.FILE),
            Pair.of(PathFragment.create("file_link"), Dirent.Type.SYMLINK),
            Pair.of(PathFragment.create("a/dir_link"), Dirent.Type.SYMLINK),
            Pair.of(PathFragment.create("a/b/dangling_link"), Dirent.Type.SYMLINK));
  }

  @Test
  public void visitTree_throwsOnUnknownDirentType() {
    FileSystem fs =
        new InMemoryFileSystem() {
          @Override
          public ImmutableList<Dirent> readdir(Path path, boolean followSymlinks) {
            return ImmutableList.of(new Dirent("?", Dirent.Type.UNKNOWN));
          }
        };
    Path treeDir = fs.getPath("/tree");

    Exception e =
        assertThrows(
            IOException.class,
            () ->
                TreeArtifactValue.visitTree(
                    treeDir, (child, type) -> fail("Should not be called")));
    assertThat(e).hasMessageThat().contains("Could not determine type of file for ? under /tree");
  }

  @Test
  public void visitTree_propagatesIoExceptionFromVisitor() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.file("tree/file");
    IOException e = new IOException("From visitor");

    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                TreeArtifactValue.visitTree(
                    treeDir,
                    (child, type) -> {
                      assertThat(child).isEqualTo(PathFragment.create("file"));
                      assertThat(type).isEqualTo(Dirent.Type.FILE);
                      throw e;
                    }));
    assertThat(thrown).isSameInstanceAs(e);
  }

  @Test
  public void visitTree_pemitsUpLevelSymlinkInsideTree() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.file("tree/file");
    scratch.dir("tree/a");
    scratch.resolve("tree/a/up_link").createSymbolicLink(PathFragment.create("../file"));
    List<Pair<PathFragment, Dirent.Type>> children = new ArrayList<>();

    TreeArtifactValue.visitTree(treeDir, (child, type) -> children.add(Pair.of(child, type)));

    assertThat(children)
        .containsExactly(
            Pair.of(PathFragment.create("file"), Dirent.Type.FILE),
            Pair.of(PathFragment.create("a"), Dirent.Type.DIRECTORY),
            Pair.of(PathFragment.create("a/up_link"), Dirent.Type.SYMLINK));
  }

  @Test
  public void visitTree_permitsAbsoluteSymlink() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.resolve("tree/absolute_link").createSymbolicLink(PathFragment.create("/tmp"));
    List<Pair<PathFragment, Dirent.Type>> children = new ArrayList<>();

    TreeArtifactValue.visitTree(treeDir, (child, type) -> children.add(Pair.of(child, type)));

    assertThat(children)
        .containsExactly(Pair.of(PathFragment.create("absolute_link"), Dirent.Type.SYMLINK));
  }

  @Test
  public void visitTree_throwsOnSymlinkPointingOutsideTree() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.file("outside");
    scratch.resolve("tree/link").createSymbolicLink(PathFragment.create("../outside"));

    Exception e =
        assertThrows(
            IOException.class,
            () ->
                TreeArtifactValue.visitTree(
                    treeDir, (child, type) -> fail("Should not be called")));
    assertThat(e).hasMessageThat().contains("/tree/link pointing to ../outside");
  }

  @Test
  public void visitTree_throwsOnSymlinkTraversingOutsideThenBackInsideTree() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.file("tree/file");
    scratch.resolve("tree/link").createSymbolicLink(PathFragment.create("../tree/file"));

    Exception e =
        assertThrows(
            IOException.class,
            () ->
                TreeArtifactValue.visitTree(
                    treeDir,
                    (child, type) -> {
                      assertThat(child).isEqualTo(PathFragment.create("file"));
                      assertThat(type).isEqualTo(Dirent.Type.FILE);
                    }));
    assertThat(e).hasMessageThat().contains("/tree/link pointing to ../tree/file");
  }

  /** Parameterized tests for {@link TreeArtifactValue.MultiBuilder}. */
  @RunWith(Parameterized.class)
  public static final class MultiBuilderTest {

    private static final ArtifactRoot ROOT =
        ArtifactRoot.asDerivedRoot(new InMemoryFileSystem().getPath("/root"), "bin");

    private enum MultiBuilderType {
      BASIC {
        @Override
        TreeArtifactValue.MultiBuilder newMultiBuilder() {
          return TreeArtifactValue.newMultiBuilder();
        }
      },
      CONCURRENT {
        @Override
        TreeArtifactValue.MultiBuilder newMultiBuilder() {
          return TreeArtifactValue.newConcurrentMultiBuilder();
        }
      };

      abstract TreeArtifactValue.MultiBuilder newMultiBuilder();
    }

    @Parameter public MultiBuilderType multiBuilderType;
    private final Map<SpecialArtifact, TreeArtifactValue> results = new HashMap<>();

    @Parameters(name = "{0}")
    public static MultiBuilderType[] params() {
      return MultiBuilderType.values();
    }

    @Test
    public void empty() {
      TreeArtifactValue.MultiBuilder treeArtifacts = multiBuilderType.newMultiBuilder();

      treeArtifacts.injectTo(results::put);

      assertThat(results).isEmpty();
    }

    @Test
    public void singleTreeArtifact() {
      TreeArtifactValue.MultiBuilder treeArtifacts = multiBuilderType.newMultiBuilder();
      SpecialArtifact parent = createTreeArtifact("bin/tree");
      TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(parent, "child1");
      TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(parent, "child2");

      treeArtifacts
          .putChild(child1, metadataWithId(1))
          .putChild(child2, metadataWithId(2))
          .injectTo(results::put);

      assertThat(results)
          .containsExactly(
              parent,
              TreeArtifactValue.newBuilder(parent)
                  .putChild(child1, metadataWithId(1))
                  .putChild(child2, metadataWithId(2))
                  .build());
    }

    @Test
    public void multipleTreeArtifacts() {
      TreeArtifactValue.MultiBuilder treeArtifacts = multiBuilderType.newMultiBuilder();
      SpecialArtifact parent1 = createTreeArtifact("bin/tree1");
      TreeFileArtifact parent1Child1 = TreeFileArtifact.createTreeOutput(parent1, "child1");
      TreeFileArtifact parent1Child2 = TreeFileArtifact.createTreeOutput(parent1, "child2");
      SpecialArtifact parent2 = createTreeArtifact("bin/tree2");
      TreeFileArtifact parent2Child = TreeFileArtifact.createTreeOutput(parent2, "child");

      treeArtifacts
          .putChild(parent1Child1, metadataWithId(1))
          .putChild(parent2Child, metadataWithId(3))
          .putChild(parent1Child2, metadataWithId(2))
          .injectTo(results::put);

      assertThat(results)
          .containsExactly(
              parent1,
              TreeArtifactValue.newBuilder(parent1)
                  .putChild(parent1Child1, metadataWithId(1))
                  .putChild(parent1Child2, metadataWithId(2))
                  .build(),
              parent2,
              TreeArtifactValue.newBuilder(parent2)
                  .putChild(parent2Child, metadataWithId(3))
                  .build());
    }

    private static SpecialArtifact createTreeArtifact(String execPath) {
      return TreeArtifactValueTest.createTreeArtifact(execPath, ROOT);
    }
  }

  private SpecialArtifact createTreeArtifact(String execPath) {
    return createTreeArtifact(execPath, root);
  }

  private static SpecialArtifact createTreeArtifact(String execPath, ArtifactRoot root) {
    return ActionsTestUtil.createTreeArtifactWithGeneratingAction(
        root, PathFragment.create(execPath));
  }

  private static FileArtifactValue metadataWithId(int id) {
    return new RemoteFileArtifactValue(new byte[] {(byte) id}, id, id);
  }

  private static FileArtifactValue metadataWithIdNoDigest(int id) {
    FileArtifactValue value = mock(FileArtifactValue.class);
    when(value.getDigest()).thenReturn(null);
    when(value.getModifiedTime()).thenReturn((long) id);
    return value;
  }
}
